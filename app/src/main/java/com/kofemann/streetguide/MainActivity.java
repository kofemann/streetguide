package com.kofemann.streetguide;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;

import org.apache.http.client.HttpClient;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.SingleClientConnManager;
import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import fr.dudie.nominatim.client.JsonNominatimClient;
import fr.dudie.nominatim.model.Element;

public class MainActivity extends AppCompatActivity {

    private MapView map;
    private Button button;

    private double zoom = 19.0;

    private final Object lock = new Object();

    /**
     * Executor used to submit text-to-speech requests
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String road;

    private TextToSpeech tts;

    private LocationListener locationListener;

    // Acquire a reference to the system Location Manager
    private LocationManager locationManager;

    /**
     * minimal distance to drive before GPS calls our listener.
     */
    private int minDriveDistance = 5;

    /**
     * Current position marker
     */
    private Marker marker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context ctx = getApplicationContext();

        tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
            public void onInit(int status) {

            }
        });

        tts.setLanguage(Locale.GERMANY);

        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        setContentView(R.layout.activity_main);

        button = findViewById(R.id.street_name_id);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                synchronized (lock) {
                    if (road != null) {
                        tts.speak(road, TextToSpeech.QUEUE_ADD, null);
                    }
                }
            }
        });


        map = findViewById(R.id.map);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setBuiltInZoomControls(true);
        map.setMultiTouchControls(true);

        marker = new Marker(map, this);
        map.getOverlays().add(marker);

        map.addMapListener(new MapListener() {
            @Override
            public boolean onScroll(ScrollEvent event) {
                return false;
            }

            @Override
            public boolean onZoom(ZoomEvent event) {
                zoom = event.getZoomLevel();
                return false;
            }
        });


        try {

            locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

            // Register the listener with the Location Manager to receive location updates
            Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastKnownLocation != null) {
                makeUseOfNewLocation(lastKnownLocation);
            }


            // Define a listener that responds to location updates
            locationListener = new LocationListener() {
                public void onLocationChanged(final Location location) {
                    // Called when a new location is found by the network location provider.
                    makeUseOfNewLocation(location);
                }

                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                public void onProviderEnabled(String provider) {
                }

                public void onProviderDisabled(String provider) {
                }
            };

            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, minDriveDistance, locationListener);


        } catch (SecurityException e) {

        }

    }


    public void onResume() {
        super.onResume();
        map.onResume(); //needed for compass, my location overlays, v6.0.0 and up
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, minDriveDistance, locationListener);
        } catch (SecurityException e) {
            // Should never happen
        }
    }

    public void onPause() {
        super.onPause();
        map.onPause();  //needed for compass, my location overlays, v6.0.0 and up
    }

    private void makeUseOfNewLocation(final Location location) {

        IMapController mapController = map.getController();
        mapController.setZoom(zoom);
        GeoPoint point = new GeoPoint(location);
        mapController.setCenter(point);

        marker.setPosition(point);

        Future<String> f = executor.submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                String newRoad = getStritName(location);
                return newRoad;
            }
        });

        try {
            String s = f.get(2, TimeUnit.SECONDS);
            synchronized (lock) {
                if (s != null && !s.equals(road)) {
                    road = s;
                    button.setText(road);
                    tts.speak(road, TextToSpeech.QUEUE_ADD, null);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }

    }


    private String getStritName(Location location) throws IOException {
        JsonNominatimClient nominatimClient;


        final SchemeRegistry registry = new SchemeRegistry();
        registry.register(new Scheme("https", SSLSocketFactory.getSocketFactory(), 443));
        final ClientConnectionManager connexionManager = new SingleClientConnManager(null, registry);

        final HttpClient httpClient = new DefaultHttpClient(connexionManager, null);

        final String baseUrl = "https://nominatim.openstreetmap.org/";
        final String email = "kofemann@gmail.com";
        nominatimClient = new JsonNominatimClient(baseUrl, httpClient, email);

        fr.dudie.nominatim.model.Address address = nominatimClient.getAddress(location.getLongitude(), location.getLatitude());
        for (Element e : address.getAddressElements()) {
            if (e.getKey().equals("road")) {
                return e.getValue();
            }
        }

        return null;
    }
}

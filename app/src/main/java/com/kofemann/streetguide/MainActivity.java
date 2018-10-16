package com.kofemann.streetguide;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONObject;
import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private MapView map;
    private Button button;

    private double zoom = 19.0;

    private final Object lock = new Object();

    /**
     * The location of last street notification.
     */
    private Location lastLocation;

    /**
     * Load known street.
     */
    private String road;

    /**
     * Minimal distance in meters to move before we check for new street name.
     */
    private float mixDistanceToUpdate = 15;

    private TextToSpeech tts;

    private LocationListener locationListener;

    // Acquire a reference to the system Location Manager
    private LocationManager locationManager;

    /**
     * minimal distance to drive before GPS calls our listener.
     */
    private int minDriveDistance = 0;

    /**
     * Current position marker
     */
    private Marker marker;

    /**
     * Volley request RequestQueue.
     */

    private RequestQueue queue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context ctx = getApplicationContext();

        // use speakers
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(Locale.GERMANY);
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    );
                }
            }
        });

        queue = Volley.newRequestQueue(this);

        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        setContentView(R.layout.activity_main);

        button = findViewById(R.id.street_name_id);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                synchronized (lock) {
                    if (road != null) {
                        tts.speak(road, TextToSpeech.QUEUE_ADD, null, null);
                    }
                }
            }
        });

        map = findViewById(R.id.map);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setBuiltInZoomControls(true);
        map.setMultiTouchControls(true);

        marker = new Marker(map, this);
        Drawable icon = ResourcesCompat.getDrawable(getResources(), R.drawable.ic_gps_arrow, null);

        marker.setIcon(icon);
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
            Log.e("gps", "Receiving of location events not allowed", e);
        }

    }


    public void onResume() {
        super.onResume();
        map.onResume(); //needed for compass, my location overlays, v6.0.0 and up
        queue.start();
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, minDriveDistance, locationListener);
        } catch (SecurityException e) {
            Log.e("gps", "Receiving of location events not allowed", e);
        }
    }

    public void onPause() {
        locationManager.removeUpdates(locationListener);
        super.onPause();
        map.onPause();  //needed for compass, my location overlays, v6.0.0 and up
        queue.stop();
    }

    private void makeUseOfNewLocation(final Location location) {

        IMapController mapController = map.getController();
        mapController.setZoom(zoom);
        GeoPoint point = new GeoPoint(location);
        mapController.setCenter(point);
        marker.setPosition(point);

        if (location.hasBearing()) {
            float bearing = location.getBearing();
            float direction = 360 - bearing;

            map.setMapOrientation(direction);
        }

        if (lastLocation == null || location.distanceTo(lastLocation) > mixDistanceToUpdate) {

            lastLocation = location;

            String url = String.format("https://nominatim.openstreetmap.org/reverse?email=%s&format=jsonv2&lat=%f&lon=%f",
                    "kofemann@gmai.com", lastLocation.getLatitude(), lastLocation.getLongitude());

            // Request a string response from the provided URL.
            JsonObjectRequest reverseMapRequest = new JsonObjectRequest(Request.Method.GET, url, null,
                    new Response.Listener<JSONObject>() {
                        @Override
                        public void onResponse(JSONObject response) {
                            try {
                                JSONObject address = response.getJSONObject("address");
                                if (address.has("road")) {
                                    String s = address.getString("road");
                                    if (!s.equals(road)) {
                                        road = s;
                                        button.setText(road);
                                        tts.speak(road, TextToSpeech.QUEUE_ADD, null, null);
                                    }
                                }
                            } catch (Exception e) {
                                Log.e("http", e.toString(), e);
                            }
                        }
                    }, new Response.ErrorListener() {
                @Override
                public void onErrorResponse(VolleyError error) {
                    Log.i("http", error.toString());
                }
            });

            queue.add(reverseMapRequest);
        }
    }
}

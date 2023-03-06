package dev.kofemann.streetguide;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
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
import org.osmdroid.views.overlay.TilesOverlay;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

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
    private final float mixDistanceToUpdate = 15;

    private TextToSpeech tts;
    private boolean languageSet;

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
    private MyLocationNewOverlay marker;

    /**
     * Volley request RequestQueue.
     */

    private RequestQueue queue;

    private final int REQUEST_PERMISSIONS_REQUEST_CODE = 1;

    private long lastUpdate;

    /** Light sensor to adjust map day-night mode */
    private Sensor light;
    private SensorManager sensorManager;

    /**
     * Whatever the night mode is enabled;
     */
    private boolean nightMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.getDefaultNightMode());

        Context ctx = getApplicationContext();

        tts = initTextToSpeech(ctx);

        queue = Volley.newRequestQueue(this);

        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));

        // avoid BAN rate limits bu user agent
        Configuration.getInstance().setUserAgentValue(getPackageName());
        setContentView(R.layout.activity_main);

        button = findViewById(R.id.street_name_id);
        button.setOnClickListener(v -> {
            synchronized (lock) {
                if (road != null) {
                    tts.speak(road, TextToSpeech.QUEUE_ADD, null, null);
                }
            }
        });

        map = findViewById(R.id.map);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setVisibility(View.VISIBLE);
        map.setMultiTouchControls(true);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        sensorManager.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);

        marker = new MyLocationNewOverlay(new GpsMyLocationProvider(ctx),map);
        map.getOverlays().add(marker);

        map.addMapListener(new MapListener() {
            @Override
            public boolean onScroll(ScrollEvent event) {
                return false;
            }

            @Override
            public boolean onZoom(ZoomEvent event) {
                zoom = event.getZoomLevel();
                Log.d("map", "new zoom level: " + zoom);
                return false;
            }
        });

        locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        enableLocationListener();

    }

    // some heuristics to guess the language by country code
    private Locale countryCodeToLocate(String countryCode) {

        for(Locale l : tts.getAvailableLanguages()) {
            // try to convert `en_US` into `us`
            String lang = l.toString().toLowerCase();
            String prefix = l.getLanguage().toLowerCase();
            if (lang.startsWith(prefix + "_" + countryCode)) {
                return l;
            }
        }
        return Locale.getDefault();
    }


    private TextToSpeech initTextToSpeech(Context ctx) {
        // use speakers
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        return new TextToSpeech(ctx, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .build()
                );
                tts.setPitch(1.f);
            }
        });
    }

    private void enableLocationListener() {

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNecessary(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            );
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
        // Register the listener with the Location Manager to receive location updates
        Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (lastKnownLocation != null) {
            makeUseOfNewLocation(lastKnownLocation);
        } else {
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, locationListener, null);
        }
    }

    public void onResume() {
        super.onResume();
        map.onResume(); //needed for compass, my location overlays, v6.0.0 and up
        queue.start();
        road = null;
        enableLocationListener();
    }

    public void onPause() {
        super.onPause();
        if (locationListener != null) {
            locationManager.removeUpdates(locationListener);
            road = null;
        }
        map.onPause();  //needed for compass, my location overlays, v6.0.0 and up
        queue.stop();
    }

    private boolean needUpdate(final Location location) {

        if (road == null || lastLocation == null) {
            Log.d("location", "road or last position is missing.");
            return true;
        }

        if (location.distanceTo(lastLocation) < mixDistanceToUpdate) {
            // we still close enough
            Log.d("location", "No position update... to close.");
            return false;
        }

        if (lastUpdate + TimeUnit.SECONDS.toMillis(10) > System.currentTimeMillis()) {
            // Reverse Geocoding once in 10 sec.
            Log.d("location", "Position-only update due to time window limit.");
            return false;
        }

        Log.d("location", "forced position update");
        return true;
    }

    private synchronized void makeUseOfNewLocation(final Location location) {

        IMapController mapController = map.getController();
        mapController.setZoom(zoom);
        GeoPoint point = new GeoPoint(location);
        mapController.setCenter(point);

        if (location.hasBearing()) {
            float bearing = location.getBearing();
            float direction = 360 - bearing;

            map.setMapOrientation(direction);
        }

        if (needUpdate(location)) {

            lastLocation = location;

            String url = String.format("https://nominatim.openstreetmap.org/reverse?email=%s&format=jsonv2&lat=%f&lon=%f",
                    "kofemann@gmail.com", lastLocation.getLatitude(), lastLocation.getLongitude());

            // Request a string response from the provided URL.
            JsonObjectRequest reverseMapRequest = new JsonObjectRequest(Request.Method.GET, url, null,
                    response -> {
                        try {
                            if (response.has("address")) {
                                JSONObject address = response.getJSONObject("address");
                                String s = address.getString("road");
                                synchronized (lock) {
                                    if (!s.equals(road)) {
                                        road = s;
                                        button.setText(road);
                                        if (!languageSet) {
                                            Locale locale = address.has("country_code") ?
                                                    countryCodeToLocate(address.getString("country_code"))
                                                    : Locale.getDefault();
                                            tts.setLanguage(locale);
                                            languageSet = true;
                                        }
                                        tts.speak(road, TextToSpeech.QUEUE_ADD, null, null);
                                    }
                                }
                            }
                            Log.d("http", response.toString());
                        } catch (Exception e) {
                            Log.e("http", e.toString(), e);
                        }
                    }, error -> Log.i("http", error.toString()));

            lastUpdate = System.currentTimeMillis();
            queue.add(reverseMapRequest);
        }
    }

    private void requestPermissionsIfNecessary(String... permissions) {
        List<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                // Permission is not granted
                permissionsToRequest.add(permission);
            }
        }
        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    permissionsToRequest.toArray(new String[0]),
                    REQUEST_PERMISSIONS_REQUEST_CODE
            );
        }
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        float lx = sensorEvent.values[0];
        Log.d("light-sensor", "lx value: " + lx);
        if (lx < SensorManager.LIGHT_CLOUDY && !nightMode) {
            Log.d("light-sensor", "enable night mode");
            map.getOverlayManager().getTilesOverlay().setColorFilter(TilesOverlay.INVERT_COLORS);
            nightMode = true;
        } else if (lx > SensorManager.LIGHT_CLOUDY && nightMode) {
            Log.d("light-sensor", "enable day mode");
            map.getOverlayManager().getTilesOverlay().setColorFilter(null);
            nightMode = false;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
        // nop
    }
}

package com.example.watchminimal;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.graphics.Color;

import android.Manifest;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    private TextView textBattery, textTime, textDate, textWeather, textWeatherIcon, textSteps;

    // Часы
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            handler.postDelayed(this, 1000);
        }
    };

    // Шаги
    private SensorManager sensorManager;
    private Sensor stepCounter;

    // Шаги "за сегодня"
    private android.content.SharedPreferences prefs;
    private static final String KEY_DAY = "day";
    private static final String KEY_BASE = "dayBase";

    // Батарея — цветовые константы
    private static final int COLOR_BATT_NORMAL = Color.parseColor("#FFFFFF"); // белый
    private static final int COLOR_BATT_CHARGE = Color.parseColor("#00E676"); // зелёный A400
    private static final int COLOR_BATT_LOW    = Color.parseColor("#FF5252"); // красный A200


    // Батарея
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);

            int pct = (level >= 0 && scale > 0) ? Math.round(100f * level / scale) : level;
            boolean charging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL);

            // Текст + цвет
            String prefix = charging ? "⚡ " : "🔋 ";
            textBattery.setText(prefix + pct + "%");

            if (charging) {
                textBattery.setTextColor(COLOR_BATT_NORMAL);
                //textBattery.setTextColor(COLOR_BATT_CHARGE); // думаю, символа достаточно
            } else if (pct >= 0 && pct < 20) {
                textBattery.setTextColor(COLOR_BATT_LOW);
            } else {
                textBattery.setTextColor(COLOR_BATT_NORMAL);
            }
        }
    };

    // Погода
    private final Handler weatherHandler = new Handler(Looper.getMainLooper());
    private final long WEATHER_INTERVAL_MS = 30 * 60 * 1000L; // 30 минут
    private final Runnable weatherTask = new Runnable() {
        @Override public void run() {
            fetchAndShowWeather();
            weatherHandler.postDelayed(this, WEATHER_INTERVAL_MS);
        }
    };
    // Дефолтные координаты (Москва)
    private static final double DEFAULT_LAT = 55.751244;
    private static final double DEFAULT_LON = 37.618423;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textBattery     = findViewById(R.id.textBattery);
        textTime        = findViewById(R.id.textTime);
        textDate        = findViewById(R.id.textDate);
        textWeather     = findViewById(R.id.textWeather);
        textWeatherIcon = findViewById(R.id.textWeatherIcon);
        textSteps       = findViewById(R.id.textSteps);

        // Датчик шагов
//        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
//        if (sensorManager != null) {
//            stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
//            if (stepCounter == null) {
//                textSteps.setText("");
//            }
//        } else {
            textSteps.setText("");
//        }

        // Префы для шагов "за сегодня"
        prefs = getSharedPreferences("stats", MODE_PRIVATE);

        // Первичное обновление времени/даты
        updateClock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Часы
        handler.post(tick);

        // Батарея
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        // Шаги (не реализовано)
//        if (sensorManager != null && stepCounter != null) {
//            sensorManager.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL);
//        }

        // Погода: сразу обновить и запланировать цикл
        textWeather.setText(getString(R.string.weather_loading));
        weatherHandler.removeCallbacks(weatherTask);
        fetchAndShowWeather();
        weatherHandler.postDelayed(weatherTask, WEATHER_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);

        try { unregisterReceiver(batteryReceiver); } catch (IllegalArgumentException ignored) {}

        if (sensorManager != null) sensorManager.unregisterListener(this);

        weatherHandler.removeCallbacks(weatherTask);
    }

    private void updateClock() {
        Date now = new Date();
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        SimpleDateFormat dateFmt = new SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault());
        textTime.setText(timeFmt.format(now));
        textDate.setText(capitalizeFirst(dateFmt.format(now)));
    }

    private String capitalizeFirst(String s) {
        if (s == null || s.isEmpty()) return s;
        char f = s.charAt(0);
        return Character.isLowerCase(f) ? Character.toUpperCase(f) + s.substring(1) : s;
    }

    // ====== Шаги "за сегодня" ======
    private String dayKey() {
        return new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
    }

    private void ensureDayBase(float totalSinceBoot) {
        String day = dayKey();
        String savedDay = prefs.getString(KEY_DAY, null);
        if (!day.equals(savedDay)) {
            prefs.edit().putString(KEY_DAY, day).putFloat(KEY_BASE, totalSinceBoot).apply();
        }
    }

    private int stepsToday(float totalSinceBoot) {
        ensureDayBase(totalSinceBoot);
        float base = prefs.getFloat(KEY_BASE, totalSinceBoot);
        return Math.max(0, Math.round(totalSinceBoot - base));
    }

    // Шагомер (не реализован)
//    @Override
    public void onSensorChanged(android.hardware.SensorEvent event) {
//        if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
//            float total = event.values[0];
//            int steps = stepsToday(total);
//            textSteps.setText(getString(R.string.steps_fmt, steps));
//        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    // Погода, Open-Meteo:
    private void fetchAndShowWeather() {
        // Получаем координаты (последняя известная) или дефолт
        final double[] latlon = new double[2];
        Location loc = getLastKnownLocation();
        if (loc != null) {
            latlon[0] = loc.getLatitude();
            latlon[1] = loc.getLongitude();
        } else {
            latlon[0] = DEFAULT_LAT;
            latlon[1] = DEFAULT_LON;
        }

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String urlStr = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%f&longitude=%f&current_weather=true&timezone=auto",
                        latlon[0], latlon[1]);
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");

                int code = conn.getResponseCode();
                if (code != 200) throw new RuntimeException("HTTP " + code);

                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject root = new JSONObject(sb.toString());
                JSONObject cw = root.getJSONObject("current_weather");
                double temp = cw.getDouble("temperature");     // °C
                double windKmh = cw.optDouble("windspeed", Double.NaN); // км/ч
                int wcode = cw.optInt("weathercode", -1);

                final String text = "Погода: " +
                        formatTemp((int)Math.round(temp)) + ", " +
                        mapWeatherCodeRu(wcode) +
                        (Double.isNaN(windKmh) ? "" :
                                String.format(Locale.getDefault(), ", ветер %.0f м/с", windKmh / 3.6));

                final String icon = mapWeatherIcon(wcode);

                runOnUiThread(() -> {
                    textWeather.setText(text);
                    textWeatherIcon.setText(icon);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    textWeather.setText(getString(R.string.weather_error));
                    textWeatherIcon.setText("—");
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private String formatTemp(int t) {
        return (t > 0 ? "+" : "") + t + "°C";
    }

    // Текст для WMO-кода:
    private String mapWeatherCodeRu(int code) {
        switch (code) {
            case 0: return "ясно";
            case 1: case 2: case 3: return "переменная облачность";
            case 45: case 48: return "туман";
            case 51: case 53: case 55: return "морось";
            case 56: case 57: return "ледяная морось";
            case 61: return "небольшой дождь";
            case 63: return "дождь";
            case 65: return "сильный дождь";
            case 66: case 67: return "ледяной дождь";
            case 71: return "небольшой снег";
            case 73: return "снег";
            case 75: return "сильный снег";
            case 77: return "снежная крупа";
            case 80: return "ливневый дождь (слаб.)";
            case 81: return "ливневый дождь";
            case 82: return "сильные ливни";
            case 85: return "снегопад (слаб.)";
            case 86: return "сильный снегопад";
            case 95: return "гроза";
            case 96: case 99: return "гроза с градом";
            default: return "—";
        }
    }

    // Эмодзи для WMO-кода:
    private String mapWeatherIcon(int code) {
        switch (code) {
            // ясно / солнце
            case 0: return "☀️";
            // облачно разной степени
            case 1: return "🌤️";
            case 2: return "⛅";
            case 3: return "☁️";
            // туман/изморозь
            case 45:
            case 48: return "🌫️";
            // морось/дождь
            case 51: case 53: case 55:
            case 56: case 57:
            case 61: case 63: return "🌧️";
            case 65:
            case 80: case 81: case 82: return "🌧️"; // ливни
            // снег
            case 71: case 73: case 75:
            case 77: case 85: case 86: return "🌨️";
            // гроза
            case 95: case 96: case 99: return "⛈️";
            default: return "—";
        }
    }

    // Локация:
    private Location getLastKnownLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 200);
            return null;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return null;

        Location best = null;
        for (String provider : lm.getProviders(true)) {
            try {
                Location loc = lm.getLastKnownLocation(provider);
                if (loc == null) continue;
                if (best == null || loc.getTime() > best.getTime()) best = loc;
            } catch (SecurityException ignored) {}
        }
        return best;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 200) {
            // попробуем обновить погоду ещё раз
            fetchAndShowWeather();
        }
    }
}

package com.scenic.pleos;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Optional Android Automotive vehicle bridge. No Pleos-specific API is assumed.
 *
 * Reflection is limited to documented public android.car APIs, so the same APK loads on
 * ordinary Android devices. Declare the optional android.car uses-library in the manifest.
 * start/stop follow Activity onStart/onStop; every callback is delivered on the main thread.
 * Never use this adapter's state as a vehicle control or a certified safety mechanism.
 */
public final class VehicleBridge {
    public interface Listener {
        /** uxRestricted also includes unknown automotive state, to fail closed for video. */
        void onVehicleState(boolean connected, Float speedKmh, boolean uxRestricted, String detail);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean automotive;
    private boolean started, connected, uxKnown, reportedUxRestricted = true;
    private int generation, managerGeneration, activeRestrictions;
    private Float speedKmh;
    private long speedAt;
    private Object car, propertyManager, uxManager, propertyCallback, uxCallback, lifecycleCallback;
    private Class<?> propertyCallbackClass;
    private String uxDetail = "UX 상태 미확인", speedDetail = "차속 미확인";

    public VehicleBridge(Context context, Listener listener) {
        // Preserve the Activity display context for display-specific CarUxRestrictions.
        // stop() disconnects every service before the Activity is destroyed.
        this.context = context;
        this.listener = listener;
        this.automotive = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    public boolean isAutomotive() { return automotive; }

    public void start() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::start); return; }
        if (started) return;
        started = true;
        int token = ++generation;
        if (!automotive) {
            listener.onVehicleState(false, null, false, "일반 Android · 실제 차량 API 없음");
            return;
        }
        publish("AAOS 차량 연결 중");
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            try {
                Class<?> callbackClass = Class.forName("android.car.Car$CarServiceLifecycleListener");
                lifecycleCallback = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                        new Class<?>[] {callbackClass}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                    if ("onLifecycleChanged".equals(method.getName()) && args != null && args.length == 2) {
                        Object callbackCar = args[0];
                        boolean ready = Boolean.TRUE.equals(args[1]);
                        main.post(() -> {
                            if (!current(token)) return;
                            car = callbackCar;
                            if (ready) attachManagers(token);
                            else {
                                releaseManagers();
                                connected = false;
                                publish("차량 서비스 연결 끊김 · 영상 차단");
                            }
                        });
                    }
                    return null;
                });
                Method create = carClass.getMethod("createCar", Context.class, Handler.class,
                        long.class, callbackClass);
                // CAR_WAIT_TIMEOUT_DO_NOT_WAIT is a documented public constant.
                long timeout = carClass.getField("CAR_WAIT_TIMEOUT_DO_NOT_WAIT").getLong(null);
                car = create.invoke(null, context, main, timeout, lifecycleCallback);
            } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException oldPlatform) {
                // API 28-compatible asynchronous connection path.
                ServiceConnection serviceConnection = new ServiceConnection() {
                    @Override public void onServiceConnected(ComponentName name, IBinder service) {
                        if (current(token)) attachManagers(token);
                    }
                    @Override public void onServiceDisconnected(ComponentName name) {
                        if (!current(token)) return;
                        releaseManagers();
                        connected = false;
                        publish("차량 서비스 연결 끊김 · 영상 차단");
                    }
                };
                car = carClass.getMethod("createCar", Context.class, ServiceConnection.class,
                        Handler.class).invoke(null, context, serviceConnection, main);
                if (car != null) carClass.getMethod("connect").invoke(car);
            }
            if (car == null) publish("차량 서비스 사용 불가 · 영상 차단");
            main.postDelayed(watchdog, 1000);
        } catch (Exception | LinkageError error) {
            connected = false;
            publish("차량 API 사용 불가 · " + reason(error) + " · 영상 차단");
        }
    }

    private boolean current(int token) { return started && generation == token; }

    private void attachManagers(int token) {
        if (!current(token) || car == null) return;
        releaseManagers();
        final int managerToken = managerGeneration;
        connected = true;
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            Method getManager = carClass.getMethod("getCarManager", String.class);
            try {
                String service = (String) carClass.getField("CAR_UX_RESTRICTION_SERVICE").get(null);
                uxManager = getManager.invoke(car, service);
                if (uxManager == null) throw new IllegalStateException("UX service unavailable");
                Class<?> callbackClass = Class.forName(
                        "android.car.drivingstate.CarUxRestrictionsManager$OnUxRestrictionsChangedListener");
                uxCallback = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                        new Class<?>[] {callbackClass}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                    if ("onUxRestrictionsChanged".equals(method.getName()) && args != null && args.length > 0) {
                        Object restriction = args[0];
                        main.post(() -> {
                            if (current(token) && managerGeneration == managerToken) readUx(restriction);
                        });
                    }
                    return null;
                });
                uxManager.getClass().getMethod("registerListener", callbackClass).invoke(uxManager, uxCallback);
                readUx(uxManager.getClass().getMethod("getCurrentCarUxRestrictions").invoke(uxManager));
            } catch (Exception | LinkageError error) {
                uxKnown = false;
                uxDetail = "UX 정보 없음(" + reason(error) + ")";
            }
            try {
                String service = (String) carClass.getField("PROPERTY_SERVICE").get(null);
                propertyManager = getManager.invoke(car, service);
                if (propertyManager == null) throw new IllegalStateException("Property service unavailable");
                int speedId = Class.forName("android.car.VehiclePropertyIds")
                        .getField("PERF_VEHICLE_SPEED").getInt(null);
                propertyCallbackClass = Class.forName(
                        "android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback");
                propertyCallback = Proxy.newProxyInstance(propertyCallbackClass.getClassLoader(),
                        new Class<?>[] {propertyCallbackClass}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                    if ("onChangeEvent".equals(method.getName()) && args != null && args.length > 0) {
                        Object value = args[0];
                        main.post(() -> {
                            if (current(token) && managerGeneration == managerToken) readSpeed(value, speedId);
                        });
                    } else if ("onErrorEvent".equals(method.getName())) {
                        main.post(() -> {
                            if (!current(token) || managerGeneration != managerToken) return;
                            speedKmh = null;
                            speedDetail = "차속 수신 오류";
                            publish(null);
                        });
                    }
                    return null;
                });
                // Available on older AAOS releases; retain duplicate continuous updates for freshness.
                Object registered = propertyManager.getClass().getMethod("registerCallback",
                        propertyCallbackClass, int.class, float.class)
                        .invoke(propertyManager, propertyCallback, speedId, 2.0f);
                if (Boolean.FALSE.equals(registered)) {
                    speedDetail = "차속 미지원 또는 권한 없음";
                } else {
                    speedDetail = "차속 첫 수신 대기";
                }
            } catch (Exception | LinkageError error) {
                speedKmh = null;
                speedDetail = "차속 정보 없음(" + reason(error) + ")";
            }
        } catch (Exception | LinkageError error) {
            connected = false;
            publish("차량 서비스 오류 · " + reason(error));
            return;
        }
        publish(null);
    }

    private void readUx(Object restrictions) {
        try {
            if (restrictions == null) throw new IllegalStateException("No current UX state");
            Class<?> type = restrictions.getClass();
            activeRestrictions = ((Number) type.getMethod("getActiveRestrictions").invoke(restrictions)).intValue();
            boolean requiresDo = Boolean.TRUE.equals(type.getMethod("isRequiresDistractionOptimization").invoke(restrictions));
            int noVideo = type.getField("UX_RESTRICTIONS_NO_VIDEO").getInt(null);
            reportedUxRestricted = requiresDo || (activeRestrictions & noVideo) != 0;
            uxKnown = true;
            uxDetail = reportedUxRestricted ? "운전 UX 제한 적용" : "차량 UX 제한 없음";
        } catch (Exception | LinkageError error) {
            uxKnown = false;
            reportedUxRestricted = true;
            uxDetail = "UX 상태 미확인";
        }
        publish(null);
    }

    private void readSpeed(Object value, int speedId) {
        try {
            if (value == null) throw new IllegalStateException("Empty property");
            Class<?> type = value.getClass();
            int propertyId = ((Number) type.getMethod("getPropertyId").invoke(value)).intValue();
            if (propertyId != speedId) return;
            int status = ((Number) type.getMethod("getStatus").invoke(value)).intValue();
            int available = type.getField("STATUS_AVAILABLE").getInt(null);
            Object raw = type.getMethod("getValue").invoke(value);
            speedKmh = status == available && raw instanceof Number
                    ? VehicleSafety.metresPerSecondToKmh((Number) raw) : null;
            speedAt = SystemClock.elapsedRealtime();
            speedDetail = speedKmh == null ? "차속 데이터 사용 불가" : "실제 차량 차속";
        } catch (Exception | LinkageError error) {
            speedKmh = null;
            speedDetail = "차속 해석 불가";
        }
        publish(null);
    }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!started || !automotive) return;
            if (speedKmh != null && SystemClock.elapsedRealtime() - speedAt > 5000) {
                speedKmh = null;
                speedDetail = "차속 데이터 오래됨";
                publish(null);
            }
            main.postDelayed(this, 1000);
        }
    };

    private void publish(String detail) {
        if (!started) return;
        boolean blocked = VehicleSafety.isVideoBlocked(automotive, connected,
                uxKnown, reportedUxRestricted, speedKmh);
        listener.onVehicleState(connected, speedKmh, blocked, detail != null ? detail
                : "AAOS · " + uxDetail + " · " + speedDetail);
    }

    /** Release listener registrations before disconnecting the Car service. */
    public void stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::stop); return; }
        if (!started) return;
        started = false;
        generation++;
        main.removeCallbacks(watchdog);
        releaseManagers();
        if (car != null) {
            try { car.getClass().getMethod("disconnect").invoke(car); }
            catch (Exception | LinkageError ignored) { /* Dead/unavailable services are already disconnected. */ }
        }
        car = null;
        lifecycleCallback = null;
        connected = false;
    }

    private void releaseManagers() {
        managerGeneration++;
        if (propertyManager != null && propertyCallback != null && propertyCallbackClass != null) {
            try { propertyManager.getClass().getMethod("unregisterCallback", propertyCallbackClass)
                    .invoke(propertyManager, propertyCallback); }
            catch (Exception | LinkageError ignored) { }
        }
        if (uxManager != null) {
            try { uxManager.getClass().getMethod("unregisterListener").invoke(uxManager); }
            catch (Exception | LinkageError ignored) { }
        }
        propertyManager = null;
        uxManager = null;
        propertyCallback = null;
        uxCallback = null;
        propertyCallbackClass = null;
        uxKnown = false;
        reportedUxRestricted = true;
        speedKmh = null;
        speedAt = 0;
        activeRestrictions = 0;
        uxDetail = "UX 상태 미확인";
        speedDetail = "차속 미확인";
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return args != null && args.length == 1 && proxy == args[0];
            case "toString": return "ScenicCarCallback";
            default: return null;
        }
    }

    private static String reason(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException && error.getCause() != null
                ? error.getCause() : error;
        if (cause instanceof SecurityException) return "권한 필요";
        if (cause instanceof ClassNotFoundException || cause instanceof LinkageError) return "API 미설치";
        return cause.getClass().getSimpleName();
    }
}

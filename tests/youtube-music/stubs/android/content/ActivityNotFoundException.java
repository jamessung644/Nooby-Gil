package android.content;

/** Test-only JVM equivalent: the Android SDK jar constructor itself throws "Stub!". */
public class ActivityNotFoundException extends RuntimeException {
    public ActivityNotFoundException(String message) { super(message); }
}

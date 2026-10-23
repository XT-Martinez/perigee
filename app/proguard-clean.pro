# Optimized Artemide only. Remove calls and their unused formatting from kept classes too.
# Debug/release variants keep their ordinary diagnostic behavior.
-assumenosideeffects class com.limelight.LimeLog {
    public static void info(java.lang.String);
    public static void warning(java.lang.String);
    public static void severe(java.lang.String);
    public static void setFileHandler(java.lang.String);
}
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace(...);
}
-assumenosideeffects class java.io.PrintStream {
    public void print(...);
    public void println(...);
    public java.io.PrintStream printf(...);
    public java.io.PrintStream format(...);
}
# Libraries also emit trace sections. Strip those, including reflective compatibility paths.
-assumenosideeffects class android.os.Trace {
    public static void beginSection(...);
    public static void endSection(...);
    public static void beginAsyncSection(...);
    public static void endAsyncSection(...);
    public static void setCounter(...);
    public static boolean isEnabled() return false;
}
-assumenosideeffects class androidx.core.os.TraceCompat {
    public static void beginSection(...);
    public static void endSection(...);
    public static void beginAsyncSection(...);
    public static void endAsyncSection(...);
    public static void setCounter(...);
    public static boolean isEnabled() return false;
}
-assumenosideeffects class androidx.tracing.Trace {
    public static void beginSection(...);
    public static void endSection(...);
    public static void beginAsyncSection(...);
    public static void endAsyncSection(...);
    public static void setCounter(...);
    public static boolean isEnabled() return false;
}
-assumenosideeffects class java.util.logging.Logger {
    public void finest(...);
    public void finer(...);
    public void fine(...);
    public void config(...);
    public void info(...);
    public void warning(...);
    public void severe(...);
    public void log(...);
    public void logp(...);
    public void logrb(...);
    public void entering(...);
    public void exiting(...);
    public void throwing(...);
}

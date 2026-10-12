// The two Xposed API types the app's entry point names. Compile-only: the Xposed framework
// (Vector, LSPosed) supplies the real ones on the phone.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

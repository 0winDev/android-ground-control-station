plugins {
    alias(libs.plugins.gcs.jvm.library)
}

dependencies {
    // UdpTransport exposes a Flow, so consumers need coroutines on their compile classpath.
    api(libs.kotlinx.coroutines.core)
}

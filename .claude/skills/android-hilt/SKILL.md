---
name: android-hilt
description: |
  Hilt DI wiring in GCS (Ground Control Station): where modules live, binding conventions,
  how JVM modules are wired without Hilt annotations. Trigger on: "Hilt", "DI", "inject",
  "@Module", "@Binds", "@Provides", "dependency injection", "wire the implementation", "qualifier".
---

# Hilt — GCS

## Build wiring

Hilt is applied through the `gcs.hilt` convention plugin (Hilt Gradle plugin + KSP + the Hilt
runtime/compiler). `gcs.android.application` and `gcs.android.feature` already include it; add it to
another Android module with:

```kotlin
plugins {
    alias(libs.plugins.gcs.android.library)
    alias(libs.plugins.gcs.hilt)
}
```

Never apply the Hilt or KSP plugins directly in a module.

## Entry points

- `@HiltAndroidApp` on `GcsApplication` (`:app`), `@AndroidEntryPoint` on `MainActivity` (`:app`).
- `@HiltViewModel` + `@Inject constructor` on ViewModels in `:feature:*`.

## Pure JVM modules have no DI annotations

`:core:mavlink`, `:core:transport` and `:core:domain` are plain Kotlin: no Hilt, no Dagger, no
`javax.inject`. Their classes are constructed by a Hilt `@Provides` in `:data:vehicle` (or `:app`).
This keeps the codec and the domain framework-free and trivially testable.

## Where modules live

| Module | DI module | Binds |
|---|---|---|
| `:data:vehicle` | `di/VehicleDataModule.kt` (when the first binding exists) | `:core:domain` repository interfaces → `Default*` implementations; `@Provides` for transport/codec objects |
| `:app` | `di/AppModule.kt` (only if needed) | App-wide objects such as an `@ApplicationScope` `CoroutineScope` |

Shape (one per owning module):

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class VehicleDataModule {

    @Binds
    abstract fun bindVehicleRepository(impl: DefaultVehicleRepository): VehicleRepository

    companion object {

        @Provides
        @Singleton
        fun provideUdpTransport(): UdpTransport = UdpTransport(/* ... */)
    }
}
```

(Illustrative — none of these types exist yet in v0.0.)

## Rules of thumb

- Constructor injection first; `@Provides` only for objects from JVM modules or factories.
- Consumers inject the contract (`VehicleRepository`), never `Default*`.
- Scope deliberately: the transport, the per-sysid vehicle state store and the link watchdog are
  `@Singleton` (one connection); stateless mappers are unscoped.
- Define each qualifier once (e.g. `@ApplicationScope`, `@IoDispatcher`), in the module that provides
  it — never duplicate a qualifier in two modules.
- DI modules have no tests; test the classes they bind.

# Integrations

How to use SceneView with the rest of your Android app stack.

---

## Jetpack Compose Navigation

Use SceneView inside navigation destinations. The scene is created when you navigate to it and destroyed when you leave — no manual cleanup.

```kotlin
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController, startDestination = "home") {
        composable("home") {
            HomeScreen(onViewProduct = { id ->
                navController.navigate("product/$id")
            })
        }
        composable("product/{id}") { backStackEntry ->
            val productId = backStackEntry.arguments?.getString("id") ?: return@composable
            ProductViewerScreen(productId)
        }
        composable("ar-preview") {
            ARPreviewScreen()
        }
    }
}

@Composable
fun ProductViewerScreen(productId: String) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val model = rememberModelInstance(modelLoader, "models/$productId.glb")

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        cameraManipulator = rememberCameraManipulator()
    ) {
        model?.let { ModelNode(modelInstance = it, scaleToUnits = 1.0f) }
    }
}
```

!!! tip "Engine lifecycle"
    Each `rememberEngine()` call creates a new Filament engine. If you navigate between multiple 3D screens frequently, consider sharing the engine via a ViewModel or CompositionLocal to avoid repeated initialization.

### Shared engine across destinations

```kotlin
// In your Application or top-level composable
val LocalEngine = staticCompositionLocalOf<Engine> { error("No engine") }

@Composable
fun App() {
    val engine = rememberEngine()

    CompositionLocalProvider(LocalEngine provides engine) {
        AppNavigation()
    }
}

// In any destination
@Composable
fun ProductViewer() {
    val engine = LocalEngine.current
    val modelLoader = rememberModelLoader(engine)
    // ...
}
```

---

## Material 3 / Material Design

SceneView renders inside a standard Compose layout. Wrap it with Material 3 components freely.

### 3D viewer in a Material 3 card

```kotlin
@Composable
fun ProductCard(product: Product) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            // 3D viewer as the card hero
            SceneView(
                modifier = Modifier.fillMaxWidth().height(250.dp),
                cameraManipulator = rememberCameraManipulator()
            ) {
                rememberModelInstance(modelLoader, product.modelPath)?.let {
                    ModelNode(modelInstance = it, scaleToUnits = 1.0f)
                }
            }

            // Standard Material 3 content below
            Column(modifier = Modifier.padding(16.dp)) {
                Text(product.name, style = MaterialTheme.typography.headlineSmall)
                Text(product.price, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { /* add to cart */ }) {
                    Text("Add to Cart")
                }
            }
        }
    }
}
```

### Bottom sheet with AR

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ARWithBottomSheet() {
    val sheetState = rememberModalBottomSheetState()
    var showSheet by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            planeRenderer = true
        ) {
            // AR content
        }

        // Floating action button
        FloatingActionButton(
            onClick = { showSheet = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Icon(Icons.Default.Settings, "Settings")
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState) {
            // Model picker, settings, etc.
            ModelPickerContent()
        }
    }
}
```

---

## ViewModel integration

Keep scene state in a ViewModel so it survives configuration changes.

```kotlin
class SceneViewModel : ViewModel() {
    var selectedModel by mutableStateOf("helmet")
        private set

    var isAnimating by mutableStateOf(true)
        private set

    var lightIntensity by mutableFloatStateOf(100_000f)
        private set

    fun selectModel(name: String) { selectedModel = name }
    fun toggleAnimation() { isAnimating = !isAnimating }
    fun setLight(intensity: Float) { lightIntensity = intensity }
}

@Composable
fun SceneScreen(viewModel: SceneViewModel = viewModel()) {
    val model = rememberModelInstance(modelLoader, "models/${viewModel.selectedModel}.glb")

    SceneView(modifier = Modifier.fillMaxSize()) {
        model?.let {
            ModelNode(
                modelInstance = it,
                scaleToUnits = 1.0f,
                autoAnimate = viewModel.isAnimating
            )
        }
        LightNode(
            type = LightManager.Type.SUN,
            apply = { intensity(viewModel.lightIntensity) }
        )
    }
}
```

---

## Hilt / dependency injection

Inject model paths, environment configurations, or feature flags.

```kotlin
@HiltViewModel
class ProductViewModel @Inject constructor(
    private val productRepository: ProductRepository
) : ViewModel() {
    val product = productRepository.getProduct(productId)
    val modelUrl get() = product.value?.modelUrl
}

@Composable
fun ProductScreen(viewModel: ProductViewModel = hiltViewModel()) {
    val product by viewModel.product.collectAsStateWithLifecycle()

    product?.modelUrl?.let { url ->
        SceneView(modifier = Modifier.fillMaxSize()) {
            rememberModelInstance(modelLoader, url)?.let {
                ModelNode(modelInstance = it, scaleToUnits = 1.0f)
            }
        }
    }
}
```

---

## ARCore Cloud — Cloud Anchors / Geospatial / Streetscape

The `CloudAnchorNode` example below (and any code that enables `Config.CloudAnchorMode.ENABLED`,
`Config.GeospatialMode.ENABLED`, or `Config.StreetscapeGeometryMode.ENABLED`) hits Google's
ARCore Cloud backend. You need:

1. **ARCore API enabled** at <https://console.cloud.google.com/apis/library/arcore.googleapis.com>
2. **Billing on** for the project (Geospatial endpoints are paid; free tier is generous for dev)
3. **A restricted API key** (Android apps → your package + signing SHA-1)
4. **`ACCESS_FINE_LOCATION` runtime permission** before `Session.configure(GeospatialMode.ENABLED)`
   (it throws `FineLocationPermissionNotGrantedException` otherwise)

Wire the key into `AndroidManifest.xml`:

```xml
<application>
    <meta-data
        android:name="com.google.android.ar.API_KEY"
        android:value="${arcoreApiKey}" />
</application>
```

Inject from env or `local.properties` at build time (never commit):

```groovy
// app/build.gradle
android.defaultConfig {
    def key = System.getenv("ARCORE_API_KEY") ?: ""
    if (key.isEmpty()) {
        def f = rootProject.file("local.properties")
        if (f.exists()) {
            def p = new Properties(); f.withInputStream { p.load(it) }
            key = p.getProperty("ARCORE_API_KEY", "")
        }
    }
    manifestPlaceholders["arcoreApiKey"] = key
}
```

Step-by-step Cloud Console setup (project, billing, API enable, key restrictions):
[`samples/android-demo/ARCORE_CLOUD_SETUP.md`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/ARCORE_CLOUD_SETUP.md).

> Plain plane-finding, hit-testing, face mesh, image detection, and AR camera streaming do
> NOT need the API key. Only the three modes above hit the Cloud backend.

---

## Room / local database

Store anchor data for persistent AR experiences.

```kotlin
@Entity
data class SavedAnchor(
    @PrimaryKey val id: String,
    val cloudAnchorId: String,
    val label: String,
    val timestamp: Long
)

@Dao
interface AnchorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(anchor: SavedAnchor)

    @Query("SELECT * FROM SavedAnchor ORDER BY timestamp DESC")
    fun getAll(): Flow<List<SavedAnchor>>
}

// In your AR composable
ARSceneView(...) {
    CloudAnchorNode(
        anchor = localAnchor,
        onHosted = { cloudId, state ->
            if (state == CloudAnchorState.SUCCESS && cloudId != null) {
                scope.launch {
                    anchorDao.save(SavedAnchor(
                        id = UUID.randomUUID().toString(),
                        cloudAnchorId = cloudId,
                        label = "My anchor",
                        timestamp = System.currentTimeMillis()
                    ))
                }
            }
        }
    ) {
        ModelNode(modelInstance = model!!)
    }
}
```

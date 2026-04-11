// ─── 3D inventory code generator ──────────────────────────────────────────────
export const ITEM_CATEGORIES = [
    "weapon", "armor", "potion", "scroll", "gem",
    "key", "tool", "food", "material", "quest",
];
export const INVENTORY_LAYOUTS = [
    "grid", "carousel", "list", "radial",
];
/**
 * Generates a complete, compilable Kotlin composable for a 3D item inventory
 * with rotation preview.
 */
export function generateInventory3D(options) {
    const { layout, categories = ["weapon", "armor", "potion", "gem"], columns = 4, showStats = true, autoRotate = true, ar = false, } = options;
    const composableName = `${capitalize(layout)}Inventory`;
    if (ar) {
        return generateArInventory(composableName, options);
    }
    return `package com.example.gaming.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.node.LightNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation

/**
 * 3D Item Inventory — ${capitalize(layout)} layout.
 *
 * Displays game items in a ${layout} layout. Selecting an item shows a 3D preview
 * with ${autoRotate ? "auto-rotation" : "manual orbit"} controls and ${showStats ? "item stats" : "item name only"}.
 *
 * Categories: ${categories.join(", ")}.
 * Each item references a GLB model at src/main/assets/models/items/<id>.glb
 *
 * Free models: Kenney Asset Pack (CC0), Quaternius Low-Poly (CC0), Sketchfab (CC BY).
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val collisionSystem = rememberCollisionSystem(engine)

    // Inventory state
    val items = remember { getSampleItems() }
    var selectedItem by remember { mutableStateOf<GameItem?>(null) }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
${autoRotate ? `    var previewRotation by remember { mutableFloatStateOf(0f) }` : ""}

    val filteredItems = if (categoryFilter != null) {
        items.filter { it.category == categoryFilter }
    } else items

    // Load selected item model
    val modelInstance = selectedItem?.let { item ->
        rememberModelInstance(modelLoader, "models/items/\${item.id}.glb")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 3D Preview ────────────────────────────────────────────────────
        if (selectedItem != null) {
            Box(
                modifier = Modifier
                    .weight(0.4f)
                    .fillMaxWidth()
            ) {
                SceneView(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    collisionSystem = collisionSystem,
                    environment = environmentLoader.createHDREnvironment(
                        assetFileLocation = "environments/studio_hdr.ktx"
                    )!!,
                    onFrame = { _ ->
${autoRotate ? `                        previewRotation += 1f` : ""}
                    }
                ) {
                    modelInstance?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = 0.8f${autoRotate ? `,
                            rotation = Rotation(y = previewRotation)` : ""}
                        )
                    }

                    // Studio lighting for item showcase
                    LightNode(
                        apply = {
                            intensity(80_000f)
                            color(1.0f, 0.98f, 0.95f)
                            direction(0.3f, -1f, -0.5f)
                        }
                    )

                    LightNode(
                        apply = {
                            intensity(30_000f)
                            color(0.9f, 0.9f, 1.0f)
                            direction(-0.5f, -0.3f, 0.5f)
                        }
                    )
                }

                // Loading indicator
                if (modelInstance == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

${showStats ? `            // Item stats panel
            selectedItem?.let { item ->
                Surface(
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(item.category.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        Text(item.description, style = MaterialTheme.typography.bodyMedium)
                        if (item.stats.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            for ((stat, value) in item.stats) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(stat, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    Text(value.toString(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }` : ""}
        }

        // ── Category Filter ───────────────────────────────────────────────
        ScrollableTabRow(
            selectedTabIndex = if (categoryFilter == null) 0
                else categories.indexOf(categoryFilter) + 1,
            modifier = Modifier.fillMaxWidth()
        ) {
            Tab(selected = categoryFilter == null, onClick = { categoryFilter = null }) {
                Text("All", modifier = Modifier.padding(12.dp))
            }
${categories
        .map((cat) => `            Tab(selected = categoryFilter == "${cat}", onClick = { categoryFilter = "${cat}" }) {
                Text("${capitalize(cat)}", modifier = Modifier.padding(12.dp))
            }`)
        .join("\n")}
        }

        // ── Item Grid ─────────────────────────────────────────────────────
        LazyVerticalGrid(
            columns = GridCells.Fixed(${columns}),
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier.weight(if (selectedItem != null) 0.4f else 1f)
        ) {
            items(filteredItems) { item ->
                ItemCard(
                    item = item,
                    isSelected = item == selectedItem,
                    onClick = { selectedItem = if (selectedItem == item) null else item }
                )
            }
        }
    }
}

/**
 * Single item card in the inventory grid.
 */
@Composable
private fun ItemCard(
    item: GameItem,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .padding(4.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(8.dp)
        ) {
            // Icon placeholder (use item.icon resource in production)
            Text(
                text = item.icon,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
            if (item.quantity > 1) {
                Text(
                    text = "x\${item.quantity}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Game item data model.
 */
data class GameItem(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val icon: String,
    val quantity: Int = 1,
    val rarity: String = "common",
    val stats: Map<String, Int> = emptyMap(),
)

/**
 * Sample items for demonstration.
 */
private fun getSampleItems(): List<GameItem> {
    return listOf(
        GameItem("iron_sword", "Iron Sword", "weapon", "A sturdy iron blade.", "\\u2694\\uFE0F", stats = mapOf("Attack" to 15, "Speed" to 10)),
        GameItem("steel_shield", "Steel Shield", "armor", "Blocks incoming damage.", "\\uD83D\\uDEE1\\uFE0F", stats = mapOf("Defense" to 20, "Weight" to 8)),
        GameItem("health_potion", "Health Potion", "potion", "Restores 50 HP.", "\\uD83E\\uDDEA", quantity = 5, stats = mapOf("Heal" to 50)),
        GameItem("fire_scroll", "Fire Scroll", "scroll", "Casts fireball spell.", "\\uD83D\\uDCDC", quantity = 3, stats = mapOf("Damage" to 30, "Mana" to 15)),
        GameItem("ruby_gem", "Ruby Gem", "gem", "A precious red gemstone.", "\\uD83D\\uDC8E", rarity = "rare", stats = mapOf("Value" to 100)),
        GameItem("dungeon_key", "Dungeon Key", "key", "Opens locked doors.", "\\uD83D\\uDD11"),
        GameItem("pickaxe", "Pickaxe", "tool", "Mines ore deposits.", "\\u26CF\\uFE0F", stats = mapOf("Mining" to 12)),
        GameItem("bread", "Bread", "food", "Restores 10 hunger.", "\\uD83C\\uDF5E", quantity = 8, stats = mapOf("Hunger" to 10)),
        GameItem("iron_ore", "Iron Ore", "material", "Raw iron for smelting.", "\\uD83E\\uDEA8", quantity = 12),
        GameItem("magic_amulet", "Magic Amulet", "quest", "Belongs to the wizard.", "\\uD83D\\uDD2E", rarity = "legendary"),
    )
}`;
}
function generateArInventory(composableName, options) {
    return `package com.example.gaming.inventory

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.math.Position

/**
 * AR Item Inventory — ${capitalize(options.layout)} layout.
 *
 * View game items in augmented reality at real-world scale.
 * Tap a surface to place the selected item for inspection.
 *
 * Required AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.CAMERA" />
 *   <uses-feature android:name="android.hardware.camera.ar" android:required="true" />
 *   <meta-data android:name="com.google.ar.core" android:value="required" />
 *
 * Gradle: implementation("io.github.sceneview:arsceneview:3.6.0")
 */
@Composable
fun ${composableName}AR() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val collisionSystem = rememberCollisionSystem(engine)

    val modelInstance = rememberModelInstance(modelLoader, "models/items/iron_sword.glb")

    var placed by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            collisionSystem = collisionSystem,
            planeRenderer = true,
            onTapAR = { hitResult ->
                if (!placed && modelInstance != null) {
                    val anchor = hitResult.createAnchor()
                    placed = true
                }
            }
        ) {
            modelInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = 0.3f
                )
            }
        }

        if (!placed) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
            ) {
                Text(
                    text = "Tap a surface to place item for AR inspection",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}`;
}
function capitalize(s) {
    return s
        .split("-")
        .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
        .join("");
}

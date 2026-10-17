package io.github.sceneview.demo.common.placement

import java.util.Locale

/**
 * One real, everyday product the AR placement catalogue offers (#4560).
 *
 * [id] is the HD pack id (`ikea-nockeby-sofa`), shared with the Model Viewer's catalogue and the
 * iOS demo; the thumbnail and the bundled file are both derived from it. [sizeMeters] is the real
 * object's longest dimension — the number `ModelNode(scaleToUnits = …)` receives, so a sofa
 * lands at 2.30 m and a speaker at 18 cm. The figures are the ones the website's
 * catalogue (`website-static/view.html`) and the Model Viewer already show for the same files.
 */
data class RealProduct(
    val id: String,
    val displayName: String,
    val sizeMeters: Float,
) {
    /** File stem shared by the bundled GLB and the `hd_` thumbnail (`ikea_nockeby_sofa`). */
    val stem: String get() = id.replace('-', '_')

    /** Key of this product's picker thumbnail in `ModelThumbnails`. */
    val thumbnailStem: String get() = "hd_$stem"
}

/**
 * The products shipped inside the APK — available offline, placed the moment they are chosen.
 *
 * Seven pieces from the HD pack (`hd-pack-v1`), registered in `assets/manifest.json` as
 * `models/<stem>.glb` and fetched by `tools/fetch-assets.sh` like every other demo asset. They
 * cover what the catalogue promises — furniture, a home appliance, tech and a car — in under
 * 3 MB together. They replace the Khronos / three.js sample assets (Toy Car, Soldier, Lantern…)
 * that used to open this catalogue: those are test payloads, not things anyone puts in a room.
 */
val BUNDLED_REAL_PRODUCTS: List<RealProduct> = listOf(
    RealProduct("ikea-nockeby-sofa", "IKEA NOCKEBY sofa", 2.30f),
    RealProduct("ikea-kallax-shelf", "IKEA KALLAX shelf", 1.47f),
    RealProduct("ikea-linnmon-alex-desk", "IKEA LINNMON / ALEX desk", 1.50f),
    RealProduct("dyson-hot-cool-fan", "Dyson Hot+Cool fan", 0.63f),
    RealProduct("jbl-charge-speaker", "JBL Charge speaker", 0.18f),
    RealProduct("ue-boom-speaker", "Ultimate Ears BOOM speaker", 0.26f),
    RealProduct("tesla-model-3", "Tesla Model 3", 4.69f),
)

/**
 * The rest of the everyday catalogue, downloaded from the HD pack the first time one is chosen.
 * Same ids, names and real sizes as the Model Viewer's shelves. The museum and space scans are
 * left out on purpose: this list is "will it fit in my room", not a gallery.
 */
val DOWNLOADABLE_REAL_PRODUCTS: List<RealProduct> = listOf(
    // Furniture
    RealProduct("ikea-trensum-mirror", "IKEA TRENSUM mirror", 0.33f),
    RealProduct("rivet-sloane-sofa", "Rivet Sloane sofa", 2.14f),
    RealProduct("stone-beam-dalton-sofa", "Stone & Beam Dalton sofa", 2.37f),
    RealProduct("rivet-aiden-armchair", "Rivet Aiden armchair", 0.93f),
    RealProduct("wishbone-chair", "Wishbone dining chair", 0.74f),
    RealProduct("rivet-arc-floor-lamp", "Rivet arc floor lamp", 1.93f),
    RealProduct("rivet-anne-desk", "Rivet Anne desk", 1.57f),
    RealProduct("rivet-media-console", "Rivet media console", 1.22f),
    // Cars
    RealProduct("peugeot-2008", "Peugeot 2008", 4.30f),
    RealProduct("mini-cooper-s", "MINI Cooper S", 3.63f),
    RealProduct("fiat-500-classic", "Fiat 500 (classic)", 2.97f),
    // Bikes and scooters
    RealProduct("city-bike", "City bike", 1.75f),
    RealProduct("mongoose-tyax-mtb", "Mongoose Tyax mountain bike", 1.75f),
    RealProduct("tier-escooter", "TIER e-scooter", 1.15f),
    RealProduct("vespa-et3", "Vespa Primavera ET3", 1.69f),
    // Home
    RealProduct("kitchenaid-stand-mixer", "KitchenAid stand mixer", 0.53f),
    RealProduct("smeg-toaster", "SMEG toaster", 0.31f),
    RealProduct("glass-kettle", "Glass kettle", 0.25f),
    RealProduct("amazonbasics-portable-ac", "AmazonBasics portable AC", 0.61f),
    RealProduct("glass-door-fridge", "Glass-door fridge", 2.18f),
    // Tech
    RealProduct("iphone-13-pro-max", "iPhone 13 Pro Max", 0.16f),
    RealProduct("galaxy-s21-ultra", "Samsung Galaxy S21 Ultra", 0.17f),
    RealProduct("macbook-pro-13", "MacBook Pro 13\"", 0.30f),
    RealProduct("ipad-mini-6", "iPad mini 6", 0.20f),
    RealProduct("apple-watch-series-7", "Apple Watch Series 7", 0.045f),
    RealProduct("airpods-pro", "AirPods Pro", 0.080f),
    RealProduct("nintendo-switch-oled", "Nintendo Switch OLED", 0.24f),
    RealProduct("xbox-wireless-controller", "Xbox Wireless Controller", 0.16f),
    RealProduct("sony-mdr-7506", "Sony MDR-7506 headphones", 0.22f),
    RealProduct("skullcandy-crusher", "Skullcandy Crusher headphones", 0.19f),
    RealProduct("jbl-tour-one-m3-v2", "JBL Tour One M3 headphones", 0.19f),
    RealProduct("gopro-hero-8", "GoPro HERO8", 0.066f),
    RealProduct("canon-eos-rp-v2", "Canon EOS RP camera", 0.13f),
    RealProduct("instax-mini-8", "Instax Mini 8 camera", 0.12f),
    RealProduct("dji-mavic-3-v2", "DJI Mavic 3 drone", 0.35f),
    RealProduct("desktop-3d-printer", "Desktop 3D printer", 0.45f),
    // Fashion and sport
    RealProduct("air-jordan-1", "Air Jordan 1", 0.30f),
    RealProduct("nike-pegasus-36", "Nike Pegasus 36", 0.29f),
    RealProduct("converse-chuck-taylor", "Converse Chuck Taylor", 0.29f),
    RealProduct("adidas-high-top", "adidas high-top sneaker", 0.30f),
    RealProduct("reebok-fueltrain", "Reebok FuelTrain", 0.27f),
    RealProduct("ugg-classic-tall", "UGG Classic Tall boot", 0.33f),
    RealProduct("jansport-backpack", "JanSport backpack", 0.44f),
    RealProduct("rolex-watch-v2", "Rolex watch", 0.075f),
    RealProduct("moto-helmet-v2", "Motorcycle helmet", 0.33f),
    RealProduct("tennis-racket", "Tennis racket", 0.69f),
    RealProduct("photorealistic-guitar-v2", "Acoustic guitar", 1.00f),
)

/** The bundled row for [product]: an `assets/` path, placed at the product's real size. */
fun RealProduct.bundledPlacementRow(): PlacementModel = PlacementModel(
    id = id,
    displayName = displayName,
    assetLocation = "models/$stem.glb",
    realWorldSizeMeters = sizeMeters,
    sizeIsMeasured = true,
    thumbnailStem = thumbnailStem,
)

/**
 * The HD-pack row for [product]. [fileLocation] is the `file://` URI the pack stores it at — the
 * same path before and after the download, which is why the row carries [ready] separately: a row
 * that is not on disk yet is `pending`, so the session places nothing until the real file lands.
 */
fun RealProduct.downloadablePlacementRow(
    fileLocation: String,
    ready: Boolean,
    downloading: Boolean,
): PlacementModel = PlacementModel(
    id = id,
    displayName = displayName,
    assetLocation = fileLocation,
    realWorldSizeMeters = sizeMeters,
    sizeIsMeasured = true,
    source = PlacementModelSource.Downloadable,
    pending = !ready,
    downloading = downloading,
    thumbnailStem = thumbnailStem,
)

/**
 * A real size as the picker card prints it: centimetres under a metre ("18 cm", "4.5 cm"),
 * metres with two decimals above ("2.30 m"). Matches the website catalogue's own labels.
 */
fun formatRealSize(meters: Float): String = when {
    meters < CENTIMETRE_DECIMALS_BELOW_M ->
        String.format(Locale.ROOT, "%.1f cm", meters * CENTIMETRES_PER_METRE)
    meters < 1f -> String.format(Locale.ROOT, "%.0f cm", meters * CENTIMETRES_PER_METRE)
    else -> String.format(Locale.ROOT, "%.2f m", meters)
}

private const val CENTIMETRES_PER_METRE = 100f
private const val CENTIMETRE_DECIMALS_BELOW_M = 0.1f

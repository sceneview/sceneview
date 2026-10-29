package io.github.sceneview.demo.ui.viewer

import io.github.sceneview.demo.R

/**
 * Thumbnail resources of the bundled models, keyed by asset stem:
 * `res/drawable-nodpi/model_thumb_<name>.webp`. Each one is a render of that exact GLB through the
 * demo's own Filament renderer, with a transparent background so it sits on the card fill of either
 * theme (#3828 — the image-generated ones drew a different Soldier). How to re-render one:
 * `tools/demo-previews/README.md`, "Model thumbnails".
 */
object ModelThumbnails {
    private val resources: Map<String, Int> = mapOf(
        "khronos_damaged_helmet" to R.drawable.model_thumb_khronos_damaged_helmet,
        "khronos_flight_helmet" to R.drawable.model_thumb_khronos_flight_helmet,
        "khronos_fox" to R.drawable.model_thumb_khronos_fox,
        "khronos_glam_velvet_sofa" to R.drawable.model_thumb_khronos_glam_velvet_sofa,
        "khronos_iridescent_dish" to R.drawable.model_thumb_khronos_iridescent_dish,
        "khronos_lantern" to R.drawable.model_thumb_khronos_lantern,
        "khronos_sheen_chair" to R.drawable.model_thumb_khronos_sheen_chair,
        "khronos_toy_car" to R.drawable.model_thumb_khronos_toy_car,
        // Fox and Shiba no longer appear in the viewer's own list (#3324) but stay mapped:
        // both GLBs still ship, and any surface that names them by stem still gets a
        // thumbnail rather than a placeholder.
        "shiba" to R.drawable.model_thumb_shiba,
        "quaternius_ranger" to R.drawable.model_thumb_quaternius_ranger,
        // HD pack scans with no bundled stand-in: keyed by the entry's `thumbnailStem`, rendered
        // from the downloaded GLB itself. The viewer also shows them on the stage until it loads.
        "hd_apollo11_exterior" to R.drawable.model_thumb_hd_apollo11_exterior,
        "hd_apollo11_interior" to R.drawable.model_thumb_hd_apollo11_interior,
        "hd_woolly_mammoth" to R.drawable.model_thumb_hd_woolly_mammoth,
        "hd_perseverance" to R.drawable.model_thumb_hd_perseverance,
        // Real-product catalogue (#4560): HD pack files, rendered by the same harness as the site tiles.
        "hd_ikea_nockeby_sofa" to R.drawable.model_thumb_hd_ikea_nockeby_sofa,
        "hd_ikea_kallax_shelf" to R.drawable.model_thumb_hd_ikea_kallax_shelf,
        "hd_ikea_linnmon_alex_desk" to R.drawable.model_thumb_hd_ikea_linnmon_alex_desk,
        "hd_ikea_trensum_mirror" to R.drawable.model_thumb_hd_ikea_trensum_mirror,
        "hd_rivet_sloane_sofa" to R.drawable.model_thumb_hd_rivet_sloane_sofa,
        "hd_stone_beam_dalton_sofa" to R.drawable.model_thumb_hd_stone_beam_dalton_sofa,
        "hd_rivet_aiden_armchair" to R.drawable.model_thumb_hd_rivet_aiden_armchair,
        "hd_wishbone_chair" to R.drawable.model_thumb_hd_wishbone_chair,
        "hd_rivet_arc_floor_lamp" to R.drawable.model_thumb_hd_rivet_arc_floor_lamp,
        "hd_rivet_anne_desk" to R.drawable.model_thumb_hd_rivet_anne_desk,
        "hd_rivet_media_console" to R.drawable.model_thumb_hd_rivet_media_console,
        "hd_tesla_model_3" to R.drawable.model_thumb_hd_tesla_model_3,
        "hd_peugeot_2008" to R.drawable.model_thumb_hd_peugeot_2008,
        "hd_mini_cooper_s" to R.drawable.model_thumb_hd_mini_cooper_s,
        "hd_fiat_500_classic" to R.drawable.model_thumb_hd_fiat_500_classic,
        "hd_mongoose_tyax_mtb" to R.drawable.model_thumb_hd_mongoose_tyax_mtb,
        "hd_tier_escooter" to R.drawable.model_thumb_hd_tier_escooter,
        "hd_vespa_et3" to R.drawable.model_thumb_hd_vespa_et3,
        "hd_city_bike" to R.drawable.model_thumb_hd_city_bike,
        "hd_kitchenaid_stand_mixer" to R.drawable.model_thumb_hd_kitchenaid_stand_mixer,
        "hd_smeg_toaster" to R.drawable.model_thumb_hd_smeg_toaster,
        "hd_dyson_hot_cool_fan" to R.drawable.model_thumb_hd_dyson_hot_cool_fan,
        "hd_amazonbasics_portable_ac" to R.drawable.model_thumb_hd_amazonbasics_portable_ac,
        "hd_glass_kettle" to R.drawable.model_thumb_hd_glass_kettle,
        "hd_glass_door_fridge" to R.drawable.model_thumb_hd_glass_door_fridge,
        "hd_iphone_13_pro_max" to R.drawable.model_thumb_hd_iphone_13_pro_max,
        "hd_galaxy_s21_ultra" to R.drawable.model_thumb_hd_galaxy_s21_ultra,
        "hd_macbook_pro_13" to R.drawable.model_thumb_hd_macbook_pro_13,
        "hd_ipad_mini_6" to R.drawable.model_thumb_hd_ipad_mini_6,
        "hd_apple_watch_series_7" to R.drawable.model_thumb_hd_apple_watch_series_7,
        "hd_airpods_pro" to R.drawable.model_thumb_hd_airpods_pro,
        "hd_nintendo_switch_oled" to R.drawable.model_thumb_hd_nintendo_switch_oled,
        "hd_xbox_wireless_controller" to R.drawable.model_thumb_hd_xbox_wireless_controller,
        "hd_sony_mdr_7506" to R.drawable.model_thumb_hd_sony_mdr_7506,
        "hd_skullcandy_crusher" to R.drawable.model_thumb_hd_skullcandy_crusher,
        "hd_jbl_tour_one_m3_v2" to R.drawable.model_thumb_hd_jbl_tour_one_m3_v2,
        "hd_jbl_charge_speaker" to R.drawable.model_thumb_hd_jbl_charge_speaker,
        "hd_ue_boom_speaker" to R.drawable.model_thumb_hd_ue_boom_speaker,
        "hd_gopro_hero_8" to R.drawable.model_thumb_hd_gopro_hero_8,
        "hd_canon_eos_rp_v2" to R.drawable.model_thumb_hd_canon_eos_rp_v2,
        "hd_instax_mini_8" to R.drawable.model_thumb_hd_instax_mini_8,
        "hd_dji_mavic_3_v2" to R.drawable.model_thumb_hd_dji_mavic_3_v2,
        "hd_desktop_3d_printer" to R.drawable.model_thumb_hd_desktop_3d_printer,
        "hd_air_jordan_1" to R.drawable.model_thumb_hd_air_jordan_1,
        "hd_nike_pegasus_36" to R.drawable.model_thumb_hd_nike_pegasus_36,
        "hd_converse_chuck_taylor" to R.drawable.model_thumb_hd_converse_chuck_taylor,
        "hd_adidas_high_top" to R.drawable.model_thumb_hd_adidas_high_top,
        "hd_reebok_fueltrain" to R.drawable.model_thumb_hd_reebok_fueltrain,
        "hd_ugg_classic_tall" to R.drawable.model_thumb_hd_ugg_classic_tall,
        "hd_jansport_backpack" to R.drawable.model_thumb_hd_jansport_backpack,
        "hd_rolex_watch_v2" to R.drawable.model_thumb_hd_rolex_watch_v2,
        "hd_moto_helmet_v2" to R.drawable.model_thumb_hd_moto_helmet_v2,
        "hd_tennis_racket" to R.drawable.model_thumb_hd_tennis_racket,
        "hd_photorealistic_guitar_v2" to R.drawable.model_thumb_hd_photorealistic_guitar_v2,
        "hd_bell_x1" to R.drawable.model_thumb_hd_bell_x1,
        "hd_triceratops_skeleton" to R.drawable.model_thumb_hd_triceratops_skeleton,
        "hd_fangyi_bronze" to R.drawable.model_thumb_hd_fangyi_bronze,
        "hd_canon_at1" to R.drawable.model_thumb_hd_canon_at1,
        "hd_citroen_h_van" to R.drawable.model_thumb_hd_citroen_h_van,
        "hd_mech_drone" to R.drawable.model_thumb_hd_mech_drone,
    )
    fun resourceFor(assetName: String): Int? = resources[assetName]
}

/**
 * Same deferred-resource seam for bundled HDR environment thumbnails. Keyed by the asset stem
 * minus its resolution suffix: `environments/sky_on_fire_2k.hdr` → `sky_on_fire`.
 *
 * Every tile is rendered from the HDR it stands for (#4052): a chrome ball reflecting that exact
 * equirect, so the picture on the tile is the light the user gets. The drawable names are the
 * viewer's slot names, not file stems — `env_thumb_sunset` is the tile of the "Sunset" slot, which
 * is `sky_on_fire` since #4052. `sunset_2k.hdr` itself is an overcast seascape (it still lights the
 * home hero and the reflection-probe demo) and has no tile because no sheet offers it. The
 * "Showroom" slot has one tile per theme, like the two studio maps it stands for.
 */
object EnvironmentThumbnails {
    private val resources: Map<String, Int> = mapOf(
        "studio_showroom_dark" to R.drawable.env_thumb_studio_showroom_dark,
        "studio_showroom_light" to R.drawable.env_thumb_studio_showroom_light,
        "studio" to R.drawable.env_thumb_studio,
        "studio_warm" to R.drawable.env_thumb_studio_warm,
        "sky_on_fire" to R.drawable.env_thumb_sunset,
        "chinese_garden" to R.drawable.env_thumb_chinese_garden,
        "outdoor_cloudy" to R.drawable.env_thumb_outdoor_cloudy,
        "night_sky" to R.drawable.env_thumb_night_sky,
        "rooftop_night" to R.drawable.env_thumb_rooftop_night,
    )
    fun resourceFor(assetName: String): Int? = resources[assetName.removeSuffix("_2k")]
}

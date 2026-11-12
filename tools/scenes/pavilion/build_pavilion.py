"""Blender 5.2: original CC0 pavilion, baked PBR, portrait cameras and glTF.
Run outside the sandbox; see README.md. All artistic controls live below.
"""
import bpy
import math
import sys
import json
import shutil
from pathlib import Path
import struct
from mathutils import Vector
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent))
from assets import prepare
from build_points import write_points

# ---- Art direction: metres, linear RGB, watts and millimetres. ----
# Photographic stone sources (generated with ChatGPT Image from details of the target
# images, see LICENSES.md). When present they replace the procedural graph below.
STONE_IMAGES = {'green_marble': 'marble_green_bookmatch.jpg', 'honey_onyx': 'onyx_honey.jpg',
                'travertine': 'travertine_slab.jpg'}
TEXTURE_DIR = Path(__file__).resolve().parent / 'textures'
SEED = 2409
PREVIEW_THEMES = ('dusk',)
RENDER_SIZE = (1080, 1350)
RENDER_SAMPLES = 96
BAKE_SAMPLES = 128
STONE_RESOLUTION = 2048
DETAIL_RESOLUTION = 1024
LIGHTMAP_RESOLUTION = 2048
POINT_COUNT = 200000
JPEG_QUALITY = 92
BAKE_MARGIN = 8
MIN_BAKE_FREE_GIB = 8
TRIANGLE_LIMIT = 200000
CAMERA_SENSOR_MM = 36
CAMERAS = [  # name, eye, aim, lens; tools is orthographic, see below.
    ('rest', (-6.0, -5.85, 1.33), (2.48048, -.55081, 1.33), 25),
    ('models', (-.5, -3.5, 1.75), (1.0, -.65, 1.25), 56),
    ('materials', (-1.6, -2.5, 1.65), (.35, .6, 1.7), 44),
    ('ar', (10.2, -1.3, 1.5), (10.2, 5.0, .3), 32),
    ('scan', (-15, -18, 17), (2.7, 2.5, .7), 35),
    ('play', (-3.8, -5.65, 1.0), (2.7, -3.5, .13), 28),
    ('tools', (2.7, 2.4, 25), (2.7, 2.4, 0), 35),
]
# Horizontal sensor fit: shifts preserve parallel verticals and place the horizon at 33%.
REST_CAMERA_SHIFT = (-.15, -.2125)
TOOLS_ORTHO_SCALE = 23
ROOF_CENTER = (2.725, 4.35, 3.45)
ROOF_SIZE = (16.55, 12.8, .18)
ROOF_BEVEL = .006
ROOF_COLOR = (.88, .87, .83)
FLOOR_Z = .18
ROOF_UNDERSIDE = 3.36
COLUMN_X = (-4.05, 3.4, 6.8, 9.5)
COLUMN_Y = (-.55, 9.25)
COLUMN_RADIUS = .09
EDGE_SEGMENTS = 3
ROD_SEGMENTS = 16
SLAB_GRID = (16, 16)
SLAB_SIZE = (1.2, .8)
SLAB_JOINT = .004
SLAB_ORIGIN = (-6.0, -1.12)
SLAB_DEPTH = .24
SLAB_BEVEL = .003
TRAVERTINE_REPEAT_METRES = (2.4, 1.6)
GREEN_CENTER = (.35, .6, 1.77)
GREEN_SIZE = (4.2, .24, 3.18)
ONYX_CENTER = (-2.45, 3.03, 1.77)
ONYX_SIZE = (2.65, .07, 3.18)
STONE_EDGE = .004
STONE_ROUGHNESS = {'green_marble': .15, 'honey_onyx': .2, 'travertine': .15}
STONE_COLORS = {
    'green_marble': [(0, (.006, .022, .016)), (.38, (.018, .075, .047)),
                     (.7, (.06, .14, .09)), (1, (.19, .27, .19))],
    'honey_onyx': [(0, (.17, .049, .009)), (.25, (.56, .24, .055)),
                   (.52, (.87, .55, .20)), (.72, (.96, .82, .53)),
                   (.84, (.62, .32, .09)), (1, (.98, .88, .66))],
    'travertine': [(0, (.36, .28, .18)), (.28, (.62, .52, .37)),
                   (.65, (.83, .75, .60)), (1, (.93, .86, .73))],
}
MARBLE_NOISE = (3.8, 5.0, .72)
MARBLE_WARP = .38
MARBLE_WAVE = (2.3, 8.5, 5.0, 1.6)
MARBLE_BRANCH_SCALE = 6.5
MARBLE_VEIN_WIDTH = .045
MARBLE_VEIN_COLOR = (.86, .88, .75, 1)
MARBLE_VEIN_THRESHOLDS = (.78, .93)
ONYX_NOISE = (4.5, 4.0, .65)
ONYX_WARP = .13
ONYX_WAVE = (5.5, 5.5, 4.0, 1.8)
TRAVERTINE_NOISE = (1.0, 4.0, .7)
TRAVERTINE_STRETCH = (3.0, 65.0, 1.0)
TRAVERTINE_PORE_SCALE = 185
TRAVERTINE_PORE_THRESHOLD = .22
TRAVERTINE_PORE_DARKEN = .20
STONE_BUMP_DISTANCE = {'green_marble': .0002, 'honey_onyx': .0003, 'travertine': .0012}
STONE_BUMP_STRENGTH = .35
GLASS_COLOR = (.55, .76, .72)
GLASS_ALPHA = .15
GLASS_ROUGHNESS = .05
GLASS_Y = 2.85
MULLION_WIDTH = .025
MULLION_COLOR = (.025, .035, .035)
CURTAIN_GRID = (144, 48)
CURTAIN_X = (5.8, 8.1)
CURTAIN_Y = 1.0
CURTAIN_FOLDS = 12
CURTAIN_FOLD_DEPTH = .24
CURTAIN_SWING = -.35
CURTAIN_BREEZE_RIGHT = 1.05
CURTAIN_TEXTURE = 'velvet_crimson.jpg'
CURTAIN_UV_REPEAT = (2.0, 2.5)
CURTAIN_LIFT = .20
CURTAIN_COLOR = (.19, .0035, .014)
CURTAIN_ROUGHNESS = .72
CURTAIN_SHEEN = .8
CURTAIN_SHEEN_COLOR = (.55, .055, .10, 1)
CURTAIN_THICKNESS = .006
POOL_CENTER = (2.4, -3.685)
POOL_SIZE = (20, 4.33)
POOL_BED_Z = -.34
POOL_TILE_METRES = .1
POOL_GROUT_METRES = .002
POOL_TILE_COLOR = (.012, .065, .063, 1)
POOL_GROUT_COLOR = (.035, .09, .08, 1)
POOL_ROUGHNESS = .24
WATER_COLOR = (.012, .035, .045)
WATER_ROUGHNESS = .025
WATER_METALLIC = .15
WATER_ALPHA = .96  # Reflection dominates; runtime can replace this material.
COPING_WIDTH = .06
LAND_COLOR = (.018, .034, .022)
LAND_SIZE = (180, 180, .16)
GRAVEL_COLOR = (.29, .26, .20, 1)
GRAVEL_NOISE_SCALE = 210
GRAVEL_BAND_WIDTH = 1.6
HEDGE_Y = 24
HEDGE_COUNT = 32
HEDGE_SPACING = 3.7
HEDGE_SIZE = (2.6, 1.7, 1.5)
FOLIAGE_COLOR = (.012, .035, .016)
FOLIAGE_ALPHA_CUTOFF = .45
TREE_POSITIONS = [(13.4, -2.2, 5.2), (14.8, -1.7, 6.4),
                  (16.3, -1.1, 5.8), (17.8, -.3, 6.9),
                  (-17, 25, 4.0), (-11, 29, 4.6), (-6, 32, 3.8)]
TREE_LOBES = 11
TREE_WIDTH = .7
TREE_LOBE_HEIGHT = .8
TREE_IRREGULARITY = .18
TREE_CARD_COUNT = 7
TREE_CARD_WIDTH = 1.9
TREE_CARD_HEIGHT = 1.3
LEAF_TINT = (.14, .25, .10, 1)
FOLIAGE_SPHERE_SEGMENTS = (12, 8)
SUN_DIRECTION = (-8, -4, 2.4)  # vector toward sun; low, from camera-left.
SUN_ANGLE = .065
SUN_ENERGY = {'morning': 2.5, 'dusk': .025}
SUN_COLOR = {'morning': (1, .83, .60), 'dusk': (.28, .42, 1)}
WORLD_STRENGTH = {'morning': .45, 'dusk': .7}
# Applied to the HDR pixels, so Cycles and cmgen use the SAME blue-hour sky.
HDR_TINT = {'morning': (1.0, 1.0, 1.0), 'dusk': (.62, .79, 1.16)}
HDR_EXPOSURE = {'morning': 0.0, 'dusk': .25}
HDR_ROTATION = 0.0  # shared sky orientation; rotate runtime skybox by this angle too.
LAMP_POSITIONS = [(x, y) for y in (-.05, 3.6, 7.7)
                  for x in (-3.2, .4, 4.0, 7.6)]
MARBLE_GRAZE_POSITION = (1.0, -.05, 3.22)
MARBLE_GRAZE_TARGET = (.35, .6, 1.55)
MARBLE_GRAZE_WATTS = {'morning': 0, 'dusk': 110}
MARBLE_GRAZE_BEAM_DEGREES = 110
LAMP_COLOR = (1, .61, .28)
LAMP_WATTS = {'morning': 0, 'dusk': 190}
LAMP_BEAM_DEGREES = 100
LAMP_BLEND = .65
LAMP_RADIUS = .09
LAMP_DISC_RADIUS = .06
LAMP_DISC_DEPTH = .006
LAMP_DISC_SEGMENTS = 24
LAMP_EMISSION = {'morning': 0, 'dusk': 12}
ONYX_EMISSION = {'morning': 0, 'dusk': 4.2}
ONYX_FILL_WATTS = {'morning': 0, 'dusk': 160}
ONYX_FILL_SIZE = (2.4, 2.6)
AO_INDIRECT_WEIGHT = .35
INDIRECT_MIN = .35
LIGHTMAP_MARGIN = .006
# Existing original furniture; kept deliberately unlike a designer chair or helmet.
CHAIR_SEAT = ((-2.48, .65, .69), (1.03, 1.05, .19))
CHAIR_BACK = ((-2.48, 1.16, 1.12), (1.03, .20, .86))
CHAIR_BACK_ANGLE = 14
FURNITURE_BEVEL = .085
LEATHER_PALETTE = ((.11, .025, .008), (.49, .20, .055))
CHROME_COLOR = (.68, .73, .76)
STEEL_COLOR = (.32, .37, .4)
PLINTH_CENTER = (1.0, -.65, .63)
PLINTH_SIZE = (1.2, 1.03, .9)
SCULPTURE_SEGMENTS = (48, 24)
CHAIR_RAIL_X = (-2.93, -2.03)
CHAIR_RAIL_YZ = [((.1, .24), (1.25, .24)), ((.2, .24), (.2, .65)),
                 ((1.1, .24), (1.28, 1.4)), ((.2, .65), (1.19, .71))]
CHAIR_RAIL_RADIUS = .026
CHAIR_ARM_X = (-3.02, -1.94)
CHAIR_ARM_YZ = (.76, .98)
CHAIR_ARM_SIZE = (.085, .77, .07)
CHAIR_ARM_BEVEL = .025
SCULPTURE_LOBES = [((.89, -.65, 1.34), (.18, .23, .26), -.27),
                    ((1.11, -.65, 1.36), (.18, .23, .23), .38)]
SCULPTURE_SPINE = ((1.0, -.65, 1.08), (1.0, -.65, 1.51), .045)
SCULPTURE_INSET_COLOR = (.008, .014, .018)
LEATHER_ROUGHNESS = .42
LEATHER_NORMAL_STRENGTH = .25
CHROME_ROUGHNESS = .05
STEEL_ROUGHNESS = .3
PLASTER_ROUGHNESS = .5
MULLION_METALLIC = .65
BASIN_BED_THICKNESS = .12
BASIN_WALL_THICKNESS = .08
COPING_CENTER_Z = .15
COPING_HEIGHT = .06
WATER_Z = .13
LAND_CENTER = (0, 35, -.16)
LAND_ROUGHNESS = .95
GRAVEL_ROUGHNESS = .92
GRAVEL_STOPS = [(0, (.09, .08, .06)), (.5, GRAVEL_COLOR), (1, (.55, .50, .39))]
GRAVEL_NOISE_DETAIL = (2, .7)
GRAVEL_BUMP_DISTANCE = .008
GRAVEL_SIDE_Y = -.2
GRAVEL_SIDE_LENGTH = 11
GRAVEL_BACK_Y = 5.5
GRAVEL_Z = -.23
GRAVEL_DEPTH = .1
FOLIAGE_ROUGHNESS = .9
HEDGE_Y_JITTER = 1
HEDGE_Z = .4
TREE_TRUNK_Z = -.2
TREE_TRUNK_RADIUS = .08
TREE_LOBE_BOTTOM = .7
TREE_TOP_ALLOWANCE = 1.3
TREE_TAPER = .7
TREE_X_JITTER = .12
TREE_CARD_ANGLE_JITTER = .2
LAMP_SOURCE_DROP = .025
LAMP_WALL_TARGET_Z = 1.0
LAMP_FLOOR_TARGET_OFFSET = .3
ONYX_FILL_POSITION = (-2.45, 2.95, 1.77)
ONYX_FILL_TARGET = (-2.45, 0, .5)
CURTAIN_SWING_POWER = 3
CURTAIN_LIFT_POWER = 5

work, output = map(Path, sys.argv[sys.argv.index('--')+1:][:2])
work.mkdir(parents=True, exist_ok=True)
output.parent.mkdir(parents=True, exist_ok=True)
prepare(work)
np.random.seed(SEED)
bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete(use_global=False)
scene = bpy.context.scene
scene.render.engine = 'CYCLES'
scene.cycles.samples = RENDER_SAMPLES
scene.cycles.use_denoising = True
scene.cycles.seed = SEED
scene.render.resolution_x, scene.render.resolution_y = RENDER_SIZE
scene.render.resolution_percentage = 100
scene.render.image_settings.quality = JPEG_QUALITY
scene.view_settings.view_transform = 'AgX'
materials = {}
EMISSION = 'Emission Color'
SHEEN = 'Sheen Weight'


def blend(m):
    m.surface_render_method = 'DITHERED'


def material(name, color, rough=.4, metal=0, alpha=1, emission=0, sheen=0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    p = m.node_tree.nodes.get('Principled BSDF')
    for key, value in [('Base Color', (*color, alpha)), ('Metallic', metal),
                       ('Roughness', rough), ('Alpha', alpha), ('IOR', 1.5),
                       (SHEEN, sheen), (EMISSION, (*color, 1)), ('Emission Strength', emission)]:
        p.inputs[key].default_value = value
    m.diffuse_color = (*color, alpha)
    if alpha < 1:
        blend(m)
    materials[name] = m
    return m


def node(m, kind, **values):
    n = m.node_tree.nodes.new('ShaderNode'+kind)
    for key, value in values.items():
        n.inputs[key].default_value = value
    return n


def link(m, source, target):
    m.node_tree.links.new(source, target)


def ramp(m, source, stops):
    n = node(m, 'ValToRGB')
    r = n.color_ramp
    r.elements.remove(r.elements[1])
    for i, (pos, color) in enumerate(stops):
        e = r.elements[0] if i == 0 else r.elements.new(pos)
        e.position = pos
        e.color = (*color, 1) if len(color) == 3 else color
    link(m, source, n.inputs['Fac'])
    return n.outputs['Color']


def math_node(m, op, a, b=None):
    n = node(m, 'Math')
    n.operation = op
    for i, val in enumerate([a, b]):
        if val is not None:
            if isinstance(val, (int, float)):
                n.inputs[i].default_value = val
            else:
                link(m, val, n.inputs[i])
    return n.outputs[0]


def noise(m, coord, params):
    n = node(m, 'TexNoise', Scale=params[0], Detail=params[1], Roughness=params[2])
    link(m, coord, n.inputs['Vector'])
    return n


def warp(m, coord, params, amount):
    n = noise(m, coord, params)
    scale = node(m, 'VectorMath')
    scale.operation = 'SCALE'
    scale.inputs['Scale'].default_value = amount
    link(m, n.outputs['Color'], scale.inputs[0])
    add = node(m, 'VectorMath')
    add.operation = 'ADD'
    link(m, coord, add.inputs[0])
    link(m, scale.outputs['Vector'], add.inputs[1])
    return add.outputs['Vector']


def wave(m, coord, params, direction):
    n = node(m, 'TexWave', Scale=params[0], Distortion=params[1],
             **{'Detail': params[2], 'Detail Scale': params[3]})
    n.wave_type = 'BANDS'
    n.bands_direction = direction
    link(m, coord, n.inputs['Vector'])
    return n.outputs['Fac']


def check_bake_space():
    if shutil.disk_usage(work).free < MIN_BAKE_FREE_GIB*1024**3:
        raise RuntimeError(f'Less than {MIN_BAKE_FREE_GIB} GiB free before bake')


def bake_pbr(m, color, height, size, bump_distance=0):
    """Bake nodes on a UV plane, then replace them with portable glTF textures.
    Primary UVs are explicit on every texture and survive the later Lightmap unwrap.
    """
    check_bake_space()
    p = m.node_tree.nodes.get('Principled BSDF')
    out = m.node_tree.nodes.get('Material Output')
    bpy.ops.mesh.primitive_plane_add(size=1, location=(0, 0, -100))
    plane = bpy.context.object
    plane.data.uv_layers.active.name = 'UVMap'
    plane.data.materials.append(m)
    emit = node(m, 'Emission')
    link(m, color, emit.inputs['Color'])
    link(m, emit.outputs[0], out.inputs['Surface'])
    uv = node(m, 'UVMap')
    uv.uv_map = 'UVMap'
    textures = []
    for kind in ['EMIT'] + (['NORMAL'] if bump_distance else []):
        check_bake_space()
        image = bpy.data.images.new(m.name+'_'+kind.lower(), width=size, height=size, alpha=False)
        if kind == 'NORMAL':
            image.colorspace_settings.name = 'Non-Color'
            bump = node(m, 'Bump', Strength=STONE_BUMP_STRENGTH, Distance=bump_distance)
            link(m, height, bump.inputs['Height'])
            link(m, bump.outputs['Normal'], p.inputs['Normal'])
            link(m, p.outputs[0], out.inputs['Surface'])
        tex = node(m, 'TexImage')
        tex.image = image
        m.node_tree.nodes.active = tex
        scene.render.bake.margin = BAKE_MARGIN
        scene.cycles.samples = BAKE_SAMPLES
        bpy.ops.object.bake(type=kind)
        image.file_format = 'JPEG' if kind == 'EMIT' else 'PNG'
        image.filepath_raw = str(work/(image.name+('.jpg' if kind == 'EMIT' else '.png')))
        scene.render.image_settings.quality = JPEG_QUALITY
        image.save()
        link(m, uv.outputs['UV'], tex.inputs['Vector'])
        textures.append(tex)
    # Remove the entire procedural source graph, including bump nodes. Export sees
    # only baked textures, never unsupported procedural material features.
    keep = {p, out, uv, *textures}
    for n in list(m.node_tree.nodes):
        if n not in keep:
            m.node_tree.nodes.remove(n)
    link(m, p.outputs[0], out.inputs['Surface'])
    link(m, textures[0].outputs['Color'], p.inputs['Base Color'])
    if bump_distance:
        normal = node(m, 'NormalMap')
        normal.uv_map = 'UVMap'
        link(m, textures[1].outputs['Color'], normal.inputs['Color'])
        link(m, normal.outputs['Normal'], p.inputs['Normal'])
    bpy.data.objects.remove(plane, do_unlink=True)
    return textures[0]




def stone(name):
    m = material(name, STONE_COLORS[name][0][1], STONE_ROUGHNESS[name])
    uv = node(m, 'TexCoord').outputs['UV']
    source = TEXTURE_DIR / STONE_IMAGES[name]
    if source.exists():
        img = node(m, 'TexImage')
        img.image = bpy.data.images.load(str(source), check_existing=True)
        img.extension = 'REPEAT'
        link(m, uv, img.inputs['Vector'])
        bw = node(m, 'RGBToBW')
        link(m, img.outputs['Color'], bw.inputs['Color'])
        color, height = img.outputs['Color'], bw.outputs['Val']
    elif name == 'green_marble':
        # Fold UV.x BEFORE all noise/domain distortion: exactly mirrored slab pair.
        split = node(m, 'SeparateXYZ')
        link(m, uv, split.inputs[0])
        folded = math_node(m, 'MULTIPLY', math_node(m, 'ABSOLUTE',
                    math_node(m, 'SUBTRACT', split.outputs['X'], .5)), 2)
        coord = node(m, 'CombineXYZ')
        link(m, folded, coord.inputs['X'])
        link(m, split.outputs['Y'], coord.inputs['Y'])
        distorted = warp(m, coord.outputs[0], MARBLE_NOISE, MARBLE_WARP)
        bands = wave(m, distorted, MARBLE_WAVE, 'DIAGONAL')
        veins = ramp(m, bands, [(MARBLE_VEIN_THRESHOLDS[0], (0, 0, 0)),
                                (MARBLE_VEIN_THRESHOLDS[1], (1, 1, 1))])
        branch = node(m, 'TexVoronoi', Scale=MARBLE_BRANCH_SCALE)
        branch.feature = 'DISTANCE_TO_EDGE'
        link(m, distorted, branch.inputs['Vector'])
        small = math_node(m, 'LESS_THAN', branch.outputs['Distance'], MARBLE_VEIN_WIDTH)
        mask = math_node(m, 'MAXIMUM', veins, small)
        base = ramp(m, noise(m, distorted, MARBLE_NOISE).outputs['Fac'], STONE_COLORS[name])
        mix = node(m, 'MixRGB')
        link(m, mask, mix.inputs[0])
        link(m, base, mix.inputs[1])
        mix.inputs[2].default_value = MARBLE_VEIN_COLOR
        color, height = mix.outputs[0], bands
    elif name == 'honey_onyx':
        distorted = warp(m, uv, ONYX_NOISE, ONYX_WARP)
        height = wave(m, distorted, ONYX_WAVE, 'Y')
        color = ramp(m, height, STONE_COLORS[name])
    else:
        scale = node(m, 'VectorMath')
        scale.operation = 'MULTIPLY'
        link(m, uv, scale.inputs[0])
        scale.inputs[1].default_value = TRAVERTINE_STRETCH
        bands = noise(m, scale.outputs['Vector'], TRAVERTINE_NOISE).outputs['Fac']
        pores = node(m, 'TexVoronoi', Scale=TRAVERTINE_PORE_SCALE)
        link(m, uv, pores.inputs['Vector'])
        pits = math_node(m, 'LESS_THAN', pores.outputs['Distance'], TRAVERTINE_PORE_THRESHOLD)
        height = math_node(m, 'SUBTRACT', bands, math_node(m, 'MULTIPLY', pits, TRAVERTINE_PORE_DARKEN))
        color = ramp(m, height, STONE_COLORS[name])
    tex = bake_pbr(m, color, height, STONE_RESOLUTION, STONE_BUMP_DISTANCE[name])
    if name == 'honey_onyx':
        link(m, tex.outputs['Color'], m.node_tree.nodes.get('Principled BSDF').inputs[EMISSION])
    return m


trav = stone('travertine')
green = stone('green_marble')
onyx = stone('honey_onyx')
leather = material('leather', LEATHER_PALETTE[0], LEATHER_ROUGHNESS)
for channel, socket in [('Diffuse', 'Base Color'), ('Rough', 'Roughness'), ('nor_gl', 'Normal')]:
    tex = node(leather, 'TexImage')
    tex.image = bpy.data.images.load(str(work/('brown_leather_'+channel+'.jpg')), check_existing=True)
    uv = node(leather, 'UVMap')
    uv.uv_map = 'UVMap'
    link(leather, uv.outputs['UV'], tex.inputs['Vector'])
    target = leather.node_tree.nodes.get('Principled BSDF').inputs[socket]
    if channel != 'Diffuse':
        tex.image.colorspace_settings.name = 'Non-Color'
    if channel == 'nor_gl':
        n = node(leather, 'NormalMap', Strength=LEATHER_NORMAL_STRENGTH)
        link(leather, tex.outputs['Color'], n.inputs['Color'])
        link(leather, n.outputs['Normal'], target)
    else:
        link(leather, tex.outputs['Color'], target)
chrome = material('chrome', CHROME_COLOR, CHROME_ROUGHNESS, 1)
steel = material('brushed_steel', STEEL_COLOR, STEEL_ROUGHNESS, 1)
roof = material('white_plaster', ROOF_COLOR, PLASTER_ROUGHNESS)
red = material('crimson_velvet', CURTAIN_COLOR, CURTAIN_ROUGHNESS, sheen=CURTAIN_SHEEN)
red.node_tree.nodes.get('Principled BSDF').inputs['Sheen Tint'].default_value = CURTAIN_SHEEN_COLOR
velvet = node(red, 'TexImage')
velvet.image = bpy.data.images.load(str(TEXTURE_DIR/CURTAIN_TEXTURE), check_existing=True)
velvet_uv = node(red, 'UVMap')
velvet_uv.uv_map = 'UVMap'
link(red, velvet_uv.outputs['UV'], velvet.inputs['Vector'])
link(red, velvet.outputs['Color'], red.node_tree.nodes.get('Principled BSDF').inputs['Base Color'])
water = material('water', WATER_COLOR, WATER_ROUGHNESS, WATER_METALLIC, alpha=WATER_ALPHA)
soil = material('lawn', LAND_COLOR, LAND_ROUGHNESS)
tree = material('cypress_core', FOLIAGE_COLOR, FOLIAGE_ROUGHNESS)
black = material('sculpture_inset', SCULPTURE_INSET_COLOR, .16, .65)
glass = material('pavilion_glass', GLASS_COLOR, GLASS_ROUGHNESS, alpha=GLASS_ALPHA)
mullion = material('dark_mullions', MULLION_COLOR, STEEL_ROUGHNESS, MULLION_METALLIC)
downlight = material('recessed_downlight', LAMP_COLOR, .4)
# Fine mineral grid, baked to a texture instead of 81 metallic strips.
tile = material('basin_teal_tile', POOL_TILE_COLOR[:3], POOL_ROUGHNESS)
uv = node(tile, 'TexCoord').outputs['UV']
split = node(tile, 'SeparateXYZ')
link(tile, uv, split.inputs[0])
lines = []
for axis in ['X', 'Y']:
    scaled = math_node(tile, 'MULTIPLY', split.outputs[axis], 1/POOL_TILE_METRES)
    frac = math_node(tile, 'FRACT', scaled)
    lines.append(math_node(tile, 'LESS_THAN', frac, POOL_GROUT_METRES/POOL_TILE_METRES))
mix = node(tile, 'MixRGB')
link(tile, math_node(tile, 'MAXIMUM', *lines), mix.inputs[0])
mix.inputs[1].default_value = POOL_TILE_COLOR
mix.inputs[2].default_value = POOL_GROUT_COLOR
bake_pbr(tile, mix.outputs[0], mix.outputs[0], DETAIL_RESOLUTION)
gravel = material('gravel', GRAVEL_COLOR[:3], GRAVEL_ROUGHNESS)
uv = node(gravel, 'TexCoord').outputs['UV']
n = noise(gravel, uv, (GRAVEL_NOISE_SCALE, *GRAVEL_NOISE_DETAIL))
color = ramp(gravel, n.outputs['Fac'], GRAVEL_STOPS)
bake_pbr(gravel, color, n.outputs['Fac'], DETAIL_RESOLUTION, GRAVEL_BUMP_DISTANCE)
# A CC0 broadleaf atlas is used as anonymous distant leafy clusters, not a tree model.
leaves = material('foliage_cutout', FOLIAGE_COLOR, FOLIAGE_ROUGHNESS)
blend(leaves)
for channel, socket in [('diff', 'Base Color'), ('alpha', 'Alpha')]:
    tex = node(leaves, 'TexImage')
    tex.image = bpy.data.images.load(str(work/('foliage_'+channel+'.png')), check_existing=True)
    uv = node(leaves, 'UVMap')
    uv.uv_map = 'UVMap'
    link(leaves, uv.outputs['UV'], tex.inputs['Vector'])
    source = tex.outputs['Color']
    if channel == 'alpha':
        tex.image.colorspace_settings.name = 'Non-Color'
    else:
        tint = node(leaves, 'MixRGB')
        tint.blend_type = 'MULTIPLY'
        tint.inputs[0].default_value = 1
        tint.inputs[2].default_value = LEAF_TINT
        link(leaves, source, tint.inputs[1])
        # Bake tint into the image so the glTF texture carries it too.
        px = np.array(tex.image.pixels[:], dtype=np.float32).reshape(-1, 4)
        px[:, :3] *= np.array(LEAF_TINT[:3])
        tex.image = tex.image.copy()
        tex.image.pixels[:] = px.ravel()
        tex.image.filepath_raw = str(work/'foliage_tinted.png')
        tex.image.file_format = 'PNG'
        tex.image.save()
        leaves.node_tree.nodes.remove(tint)
    link(leaves, source, leaves.node_tree.nodes.get('Principled BSDF').inputs[socket])


def finish(o, name, mat):
    o.name = name
    o.data.materials.append(mat)
    return o


def planar_uv(o, mode='metric'):
    """UV0 is assigned before join. Wall fronts use the whole 2K slab once."""
    uv = o.data.uv_layers.active or o.data.uv_layers.new(name='UVMap')
    uv.name = 'UVMap'
    vertices = [v.co for v in o.data.vertices]
    lo = [min(v[i] for v in vertices) for i in range(3)]
    hi = [max(v[i] for v in vertices) for i in range(3)]
    for poly in o.data.polygons:
        axis = max(range(3), key=lambda i: abs(poly.normal[i]))
        axes = [i for i in range(3) if i != axis]
        for li in poly.loop_indices:
            v = o.data.vertices[o.data.loops[li].vertex_index].co
            if mode == 'slab':
                xy = [(v[a]-lo[a])/max(hi[a]-lo[a], .001) for a in axes]
            elif mode == 'travertine':
                world_v = o.matrix_world @ v
                xy = [world_v[a]/TRAVERTINE_REPEAT_METRES[k] for k, a in enumerate(axes)]
            else:
                xy = [v[a] for a in axes]
            uv.data[li].uv = xy


def box(name, loc, size, mat, bevel=0, uv_mode=None):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc)
    o = bpy.context.object
    o.dimensions = size
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    finish(o, name, mat)
    mode = uv_mode or ('slab' if mat in (green, onyx, leather) else 'travertine' if mat == trav else 'metric')
    planar_uv(o, mode)
    if bevel:
        mod = o.modifiers.new('Crisp manufactured edge', 'BEVEL')
        mod.width, mod.segments = bevel, EDGE_SEGMENTS
        bpy.ops.object.modifier_apply(modifier=mod.name)
        o.modifiers.new('Weighted normals', 'WEIGHTED_NORMAL')
    return o


def rod(name, a, b, r, mat):
    delta = Vector(b)-Vector(a)
    bpy.ops.mesh.primitive_cylinder_add(vertices=ROD_SEGMENTS, radius=r, depth=delta.length,
                                      location=(Vector(a)+Vector(b))/2)
    o = bpy.context.object
    o.rotation_euler = delta.to_track_quat('Z', 'Y').to_euler()
    return finish(o, name, mat)


box('floating_roof', ROOF_CENTER, ROOF_SIZE, roof, ROOF_BEVEL)
for x in COLUMN_X:
    for y in COLUMN_Y:
        rod('column', (x, y, FLOOR_Z), (x, y, ROOF_UNDERSIDE), COLUMN_RADIUS, chrome)
for i in range(SLAB_GRID[0]):
    for j in range(SLAB_GRID[1]):
        box('terrace_slab', (SLAB_ORIGIN[0]+i*SLAB_SIZE[0], SLAB_ORIGIN[1]+j*SLAB_SIZE[1],
                            FLOOR_Z-SLAB_DEPTH/2),
            (SLAB_SIZE[0]-SLAB_JOINT, SLAB_SIZE[1]-SLAB_JOINT, SLAB_DEPTH), trav, SLAB_BEVEL)
box('green_wall', GREEN_CENTER, GREEN_SIZE, green, STONE_EDGE)
box('onyx_slab', ONYX_CENTER, ONYX_SIZE, onyx, STONE_EDGE)
# Single-sided panes with UV0; no transmission, volume, IOR or specular extension.
for a, b in zip(COLUMN_X[:-1], COLUMN_X[1:]):
    verts = [(a, GLASS_Y, FLOOR_Z), (b, GLASS_Y, FLOOR_Z),
             (b, GLASS_Y, ROOF_UNDERSIDE), (a, GLASS_Y, ROOF_UNDERSIDE)]
    mesh = bpy.data.meshes.new('glass_pane')
    mesh.from_pydata(verts, [], [(0, 1, 2, 3)])
    o = bpy.data.objects.new('glass_pane', mesh)
    scene.collection.objects.link(o)
    finish(o, 'glass_pane', glass)
    planar_uv(o, 'slab')
    for x in (a, (a+b)/2, b):
        box('mullion', (x, GLASS_Y, (FLOOR_Z+ROOF_UNDERSIDE)/2),
            (MULLION_WIDTH, MULLION_WIDTH, ROOF_UNDERSIDE-FLOOR_Z), mullion)
    for z in (FLOOR_Z, ROOF_UNDERSIDE):
        box('glazing_rail', ((a+b)/2, GLASS_Y, z), (b-a, MULLION_WIDTH, MULLION_WIDTH), mullion)
verts, faces = [], []
nx, nz = CURTAIN_GRID
for j in range(nz+1):
    t = j/nz
    for i in range(nx+1):
        u = i/nx
        x = CURTAIN_X[0]+u*(CURTAIN_X[1]-CURTAIN_X[0])
        # Translate the lower folds sideways; their depth stays constant (no skirt).
        x += CURTAIN_BREEZE_RIGHT*t**CURTAIN_SWING_POWER*u**2
        y = CURTAIN_Y+CURTAIN_FOLD_DEPTH*math.sin(u*math.tau*CURTAIN_FOLDS)
        y += CURTAIN_SWING*t**CURTAIN_SWING_POWER*math.sin(u*math.pi/2)
        z = ROOF_UNDERSIDE-(ROOF_UNDERSIDE-FLOOR_Z)*t+CURTAIN_LIFT*t**CURTAIN_LIFT_POWER*u**2
        verts.append((x, y, z))
for j in range(nz):
    for i in range(nx):
        a = j*(nx+1)+i
        faces.append((a, a+nx+1, a+nx+2, a+1))
mesh = bpy.data.meshes.new('deep_pleats')
mesh.from_pydata(verts, [], faces)
mesh.update()
o = bpy.data.objects.new('curtain', mesh)
scene.collection.objects.link(o)
finish(o, 'curtain', red)
uv = mesh.uv_layers.new(name='UVMap')
for poly in mesh.polygons:
    for li in poly.loop_indices:
        vi = mesh.loops[li].vertex_index
        uv.data[li].uv = ((vi % (nx+1))/nx*CURTAIN_UV_REPEAT[0],
                          (vi // (nx+1))/nz*CURTAIN_UV_REPEAT[1])
for poly in mesh.polygons:
    poly.use_smooth = True
solidify = o.modifiers.new('Velvet hem thickness', 'SOLIDIFY')
solidify.thickness = CURTAIN_THICKNESS
bpy.context.view_layer.objects.active = o
bpy.ops.object.modifier_apply(modifier=solidify.name)
# Original lounge and abstract split-shell sculpture.
for x in CHAIR_RAIL_X:
    for a, b in CHAIR_RAIL_YZ:
        rod('chair_frame', (x, *a), (x, *b), CHAIR_RAIL_RADIUS, steel)
box('chair_seat', *CHAIR_SEAT, leather, FURNITURE_BEVEL)
o = box('chair_back', *CHAIR_BACK, leather, FURNITURE_BEVEL)
o.rotation_euler.x = math.radians(CHAIR_BACK_ANGLE)
for x in CHAIR_ARM_X:
    box('chair_arm', (x, *CHAIR_ARM_YZ), CHAIR_ARM_SIZE, steel, CHAIR_ARM_BEVEL)
box('hero_plinth', PLINTH_CENTER, PLINTH_SIZE, trav, STONE_EDGE)
for loc, scale, angle in SCULPTURE_LOBES:
    bpy.ops.mesh.primitive_uv_sphere_add(segments=SCULPTURE_SEGMENTS[0],
                                       ring_count=SCULPTURE_SEGMENTS[1], location=loc)
    o = bpy.context.object
    o.scale = scale
    o.rotation_euler.y = angle
    finish(o, 'split_shell', chrome)
    for poly in o.data.polygons:
        poly.use_smooth = True
rod('sculpture_spine', *SCULPTURE_SPINE, black)
# Basin is closed at the sides; its fine grid stays underneath app-replaceable water.
px, py = POOL_CENTER
pw, ph = POOL_SIZE
box('basin', (px, py, POOL_BED_Z-BASIN_BED_THICKNESS/2), (pw, ph, BASIN_BED_THICKNESS), tile)
for x in (px-pw/2, px+pw/2):
    box('basin_side', (x, py, (POOL_BED_Z+FLOOR_Z)/2),
        (BASIN_WALL_THICKNESS, ph+BASIN_WALL_THICKNESS, FLOOR_Z-POOL_BED_Z), tile)
for y in (py-ph/2, py+ph/2):
    box('basin_end', (px, y, (POOL_BED_Z+FLOOR_Z)/2),
        (pw+BASIN_WALL_THICKNESS, BASIN_WALL_THICKNESS, FLOOR_Z-POOL_BED_Z), tile)
# The near edge is under the camera. Never put a broad coping across the foreground.
box('pool_terrace_edge', (px, py+ph/2, COPING_CENTER_Z),
    (pw, COPING_WIDTH, COPING_HEIGHT), trav, SLAB_BEVEL)
mesh = bpy.data.meshes.new('water_surface')
hx, hy = pw/2, ph/2
mesh.from_pydata([(px-hx, py-hy, WATER_Z), (px+hx, py-hy, WATER_Z),
                  (px+hx, py+hy, WATER_Z), (px-hx, py+hy, WATER_Z)], [], [(0, 1, 2, 3)])
o = bpy.data.objects.new('water', mesh)
scene.collection.objects.link(o)
finish(o, 'water', water)
planar_uv(o)
box('ground', LAND_CENTER, LAND_SIZE, soil)
# Gravel skirts only, so neither the pool nor the terrace floats on a uniform lawn.
for x in (-pw/2-GRAVEL_BAND_WIDTH/2, pw/2+GRAVEL_BAND_WIDTH/2):
    box('gravel_band', (x, GRAVEL_SIDE_Y, GRAVEL_Z), (GRAVEL_BAND_WIDTH, GRAVEL_SIDE_LENGTH, GRAVEL_DEPTH), gravel)
box('gravel_band', (0, GRAVEL_BACK_Y, GRAVEL_Z), (pw, GRAVEL_BAND_WIDTH, GRAVEL_DEPTH), gravel)


def leafy_lobe(name, loc, scale):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=FOLIAGE_SPHERE_SEGMENTS[0],
                                       ring_count=FOLIAGE_SPHERE_SEGMENTS[1], location=loc)
    o = bpy.context.object
    for v in o.data.vertices:
        v.co *= 1+np.random.uniform(-TREE_IRREGULARITY, TREE_IRREGULARITY)
    o.scale = scale
    finish(o, name, tree)
    for poly in o.data.polygons:
        poly.use_smooth = True
    return o


for i in range(HEDGE_COUNT):
    x = (i-(HEDGE_COUNT-1)/2)*HEDGE_SPACING
    leafy_lobe('distant_hedge', (x, HEDGE_Y+np.random.uniform(-HEDGE_Y_JITTER, HEDGE_Y_JITTER), HEDGE_Z), HEDGE_SIZE)
for x, y, height in TREE_POSITIONS:
    rod('distant_trunk', (x, y, TREE_TRUNK_Z), (x, y, height), TREE_TRUNK_RADIUS, tree)
    for i in range(TREE_LOBES):
        t = i/(TREE_LOBES-1)
        z = TREE_LOBE_BOTTOM+t*(height-TREE_TOP_ALLOWANCE)
        width = TREE_WIDTH*(1-TREE_TAPER*t)*(1+np.random.uniform(-TREE_IRREGULARITY, TREE_IRREGULARITY))
        leafy_lobe('distant_cypress', (x+np.random.uniform(-TREE_X_JITTER, TREE_X_JITTER), y, z),
                   (width, width, TREE_LOBE_HEIGHT))
        # Radially crossed leafy cards break the silhouette of each irregular lobe.
        for k in range(TREE_CARD_COUNT):
            angle = k*math.pi/TREE_CARD_COUNT+np.random.uniform(-TREE_CARD_ANGLE_JITTER, TREE_CARD_ANGLE_JITTER)
            half = width*TREE_CARD_WIDTH/2
            dx, dy = math.cos(angle)*half, math.sin(angle)*half
            h = TREE_CARD_HEIGHT/2
            verts = [(x-dx, y-dy, z-h), (x+dx, y+dy, z-h),
                     (x+dx, y+dy, z+h), (x-dx, y-dy, z+h)]
            mesh = bpy.data.meshes.new('leaf_cluster')
            mesh.from_pydata(verts, [], [(0, 1, 2, 3)])
            o = bpy.data.objects.new('distant_leaves', mesh)
            scene.collection.objects.link(o)
            finish(o, 'distant_leaves', leaves)
            uv = mesh.uv_layers.new(name='UVMap')
            for li, value in enumerate([(0, 0), (1, 0), (1, 1), (0, 1)]):
                uv.data[li].uv = value


def aim(o, target):
    o.rotation_euler = (Vector(target)-o.location).to_track_quat('-Z', 'Y').to_euler()


for name, pos, target, lens in CAMERAS:
    bpy.ops.object.camera_add(location=pos)
    o = bpy.context.object
    o.name = 'cam_'+name
    o.data.lens = lens
    o.data.sensor_width = CAMERA_SENSOR_MM
    o.data.sensor_fit = 'HORIZONTAL'
    if name == 'rest':
        o.data.shift_x, o.data.shift_y = REST_CAMERA_SHIFT
    aim(o, target)
    if name == 'tools':
        o.data.type = 'ORTHO'
        o.data.ortho_scale = TOOLS_ORTHO_SCALE
scene.camera = bpy.data.objects['cam_rest']
bpy.ops.object.light_add(type='SUN', location=SUN_DIRECTION)
sun = bpy.context.object
sun.name = 'sun'
sun.data.angle = SUN_ANGLE
aim(sun, (0, 0, 0))
for i, (x, y) in enumerate(LAMP_POSITIONS):
    bpy.ops.mesh.primitive_cylinder_add(vertices=LAMP_DISC_SEGMENTS, radius=LAMP_DISC_RADIUS,
                                      depth=LAMP_DISC_DEPTH,
                                      location=(x, y, ROOF_UNDERSIDE-LAMP_DISC_DEPTH/2))
    finish(bpy.context.object, 'downlight_disc', downlight)
    bpy.ops.object.light_add(type='SPOT', location=(x, y, ROOF_UNDERSIDE-LAMP_SOURCE_DROP))
    o = bpy.context.object
    o.name = f'lamp_{i:02d}'
    o.data.color = LAMP_COLOR
    o.data.spot_size = math.radians(LAMP_BEAM_DEGREES)
    o.data.spot_blend = LAMP_BLEND
    o.data.shadow_soft_size = LAMP_RADIUS
    # Rear lamps rake the walls; front lamps pool onto the travertine.
    aim(o, (x, GREEN_CENTER[1] if y > 1 else y+LAMP_FLOOR_TARGET_OFFSET, LAMP_WALL_TARGET_Z if y > 1 else FLOOR_Z))
bpy.ops.object.light_add(type='SPOT', location=MARBLE_GRAZE_POSITION)
marble_graze = bpy.context.object
marble_graze.name = 'marble_graze'
marble_graze.data.color = LAMP_COLOR
marble_graze.data.spot_size = math.radians(MARBLE_GRAZE_BEAM_DEGREES)
marble_graze.data.spot_blend = LAMP_BLEND
marble_graze.data.shadow_soft_size = LAMP_RADIUS
aim(marble_graze, MARBLE_GRAZE_TARGET)
# Cycles needs a real source for onyx spill: glTF has no area lights. This is a
# render-only bounce approximation; runtime emissive still uses the band texture.
bpy.ops.object.light_add(type='AREA', location=ONYX_FILL_POSITION)
onyx_fill = bpy.context.object
onyx_fill.name = 'onyx_render_fill'
onyx_fill.data.shape = 'RECTANGLE'
onyx_fill.data.size, onyx_fill.data.size_y = ONYX_FILL_SIZE
onyx_fill.data.color = LAMP_COLOR
aim(onyx_fill, ONYX_FILL_TARGET)

# Bake exposure and tint into HDR pixels before cmgen, never just into World nodes.
for mood in ('morning', 'dusk'):
    image = bpy.data.images.load(str(work/(mood+'_source.hdr')), check_existing=True).copy()
    pixels = np.array(image.pixels[:], dtype=np.float32).reshape(-1, 4)
    pixels[:, :3] *= np.array(HDR_TINT[mood])*2**HDR_EXPOSURE[mood]
    image.pixels[:] = pixels.ravel()
    image.filepath_raw = str(work/(mood+'.hdr'))
    image.file_format = 'HDR'
    image.save()
    bpy.data.images.remove(image)
world = bpy.data.worlds.new('Poly Haven skies')
scene.world = world
world.use_nodes = True
nodes, links = world.node_tree.nodes, world.node_tree.links
env = nodes.new('ShaderNodeTexEnvironment')
coord = nodes.new('ShaderNodeTexCoord')
mapping = nodes.new('ShaderNodeMapping')
mapping.inputs['Rotation'].default_value[2] = HDR_ROTATION
links.new(coord.outputs['Normal'], mapping.inputs['Vector'])
links.new(mapping.outputs['Vector'], env.inputs['Vector'])
links.new(env.outputs['Color'], nodes.get('Background').inputs['Color'])


def lighting(mood):
    env.image = bpy.data.images.load(str(work/(mood+'.hdr')), check_existing=True)
    nodes.get('Background').inputs['Strength'].default_value = WORLD_STRENGTH[mood]
    sun.data.energy = SUN_ENERGY[mood]
    sun.data.color = SUN_COLOR[mood]
    for o in scene.objects:
        if o.type == 'LIGHT' and o.name.startswith('lamp_'):
            o.data.energy = LAMP_WATTS[mood]
            o['dusk_watts'] = LAMP_WATTS['dusk']
    downlight.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value = LAMP_EMISSION[mood]
    onyx.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value = ONYX_EMISSION[mood]
    onyx_fill.data.energy = ONYX_FILL_WATTS[mood]
    marble_graze.data.energy = MARBLE_GRAZE_WATTS[mood]
    marble_graze['dusk_watts'] = MARBLE_GRAZE_WATTS['dusk']

# Only architectural opaque surfaces share the AO atlas. Glass, foliage, terrain,
# water and luminous discs keep independent nodes and their original UV0.
bpy.ops.object.select_all(action='DESELECT')
separate_prefixes = ('water', 'glass_pane', 'distant_', 'ground', 'gravel_band', 'downlight_disc')
for o in scene.objects:
    if o.type == 'MESH' and not o.name.startswith(separate_prefixes):
        o.select_set(True)
bpy.context.view_layer.objects.active = bpy.data.objects['green_wall']
bpy.ops.object.convert(target='MESH')
bpy.ops.object.join()
baked = bpy.context.object
baked.name = 'pavilion_surfaces'
# Never overwrite UV0: the wall pair spans [0,1], floor uses metric slab UVs.
baked.data.uv_layers.new(name='Lightmap')
baked.data.uv_layers.active_index = 1
baked.data.uv_layers['Lightmap'].active_render = True
bpy.ops.object.mode_set(mode='EDIT')
bpy.ops.mesh.select_all(action='SELECT')
bpy.ops.uv.smart_project(island_margin=LIGHTMAP_MARGIN)
bpy.ops.object.mode_set(mode='OBJECT')


def bake(name, kind, size):
    check_bake_space()
    image = bpy.data.images.new(name, width=size, height=size, alpha=False)
    image.colorspace_settings.name = 'Non-Color' if name == 'ao' else 'Linear Rec.709'
    for m in baked.data.materials:
        n = node(m, 'TexImage')
        n.image = image
        n.select = True
        m.node_tree.nodes.active = n
    scene.cycles.samples = BAKE_SAMPLES
    scene.render.bake.margin = BAKE_MARGIN
    scene.render.bake.use_clear = True
    bpy.ops.object.bake(type=kind)
    image.filepath_raw = str(work/(name+'.png'))
    image.file_format = 'PNG'
    image.save()
    return image


lighting('morning')
# Sun remains ON: diffuse indirect includes its bounce underneath the roof.
# Direct directional shadows remain live rather than darkening the dusk atlas.
ao = bake('ao', 'AO', LIGHTMAP_RESOLUTION)
scene.render.bake.use_pass_direct = False
scene.render.bake.use_pass_indirect = True
scene.render.bake.use_pass_color = False
indirect = bake('indirect', 'DIFFUSE', LIGHTMAP_RESOLUTION)
a = np.array(ao.pixels[:], dtype=np.float32).reshape(-1, 4)
b = np.array(indirect.pixels[:], dtype=np.float32).reshape(-1, 4)
illum = b[:, :3].mean(axis=1)
illum = np.clip(illum/max(float(np.percentile(illum, 90)), .001), INDIRECT_MIN, 1)
a[:, :3] *= (1-AO_INDIRECT_WEIGHT)+AO_INDIRECT_WEIGHT*illum[:, None]
ao.pixels[:] = a.ravel()
ao.save()
scene.render.bake.use_pass_color = True
scene.render.bake.use_pass_direct = True
color = bake('point_colour', 'DIFFUSE', LIGHTMAP_RESOLUTION)
write_points(baked, color, work/'pavilion_points.bin', POINT_COUNT)
# Core glTF occlusion on TEXCOORD_1. It cannot carry additive or coloured GI.
group = bpy.data.node_groups.new('glTF Material Output', 'ShaderNodeTree')
group.interface.new_socket(name='Occlusion', in_out='INPUT', socket_type='NodeSocketFloat')
for m in baked.data.materials:
    n = node(m, 'Group')
    n.node_tree = group
    tex = node(m, 'TexImage')
    tex.image = ao
    uv = node(m, 'UVMap')
    uv.uv_map = 'Lightmap'
    link(m, uv.outputs['UV'], tex.inputs['Vector'])
    link(m, tex.outputs['Color'], n.inputs['Occlusion'])
baked.data.uv_layers.active_index = 0
baked.data.uv_layers['UVMap'].active_render = True
lighting('morning')
bpy.ops.wm.save_as_mainfile(filepath=str(work/'pavilion.blend'))
# Export the emissive texture and extension even though morning starts unlit.
# Reset factors in the GLB JSON below; the .blend remains in morning lighting.
onyx.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value = ONYX_EMISSION['dusk']
downlight.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value = LAMP_EMISSION['dusk']
# Area lights have no glTF equivalent. Selection excludes the preview-only source.
bpy.ops.object.select_all(action='SELECT')
onyx_fill.select_set(False)
bpy.ops.export_scene.gltf(filepath=str(output), export_format='GLB', use_selection=True,
                          export_cameras=True, export_lights=True, export_extras=True,
                          export_image_format='AUTO', export_jpeg_quality=JPEG_QUALITY, export_yup=True)
raw = output.read_bytes()
length, kind = struct.unpack_from('<II', raw, 12)
doc = json.loads(raw[20:20+length])
# Explicit core alpha modes avoid Blender-version dependent DITHERED export.
# Preserve a band-pattern emissionTexture and named dusk values for the app.
for m in doc.get('materials', []):
    if m.get('name') == leaves.name:
        m['alphaMode'] = 'MASK'
        m['alphaCutoff'] = FOLIAGE_ALPHA_CUTOFF
        m['doubleSided'] = True
    elif m.get('name') in (glass.name, water.name):
        m['alphaMode'] = 'BLEND'
        m['doubleSided'] = True
    if m.get('name') in (onyx.name, downlight.name):
        strength = ONYX_EMISSION['dusk'] if m['name'] == onyx.name else LAMP_EMISSION['dusk']
        m['extras'] = {'dusk_emissive_strength': strength,
                       'dusk_emissive_factor': m.get('emissiveFactor', [1, 1, 1])}
        m['emissiveFactor'] = [0, 0, 0]
        m.setdefault('extensions', {})['KHR_materials_emissive_strength'] = {'emissiveStrength': strength}
used = set(doc.get('extensionsUsed', []))
used.add('KHR_materials_emissive_strength')
doc['extensionsUsed'] = sorted(used)
# Repack the JSON chunk with 4-byte alignment; bufferViews are relative to BIN,
# so its payload and all texture/mesh offsets remain unchanged.
encoded = json.dumps(doc, separators=(',', ':')).encode()
encoded += b' '*((-len(encoded)) % 4)
rest = raw[20+length:]
output.write_bytes(struct.pack('<4sII', b'glTF', 2, 20+len(encoded)+len(rest))+
                   struct.pack('<II', len(encoded), kind)+encoded+rest)
allowed = {'KHR_lights_punctual', 'KHR_texture_transform', 'KHR_materials_emissive_strength',
           'KHR_materials_clearcoat', 'KHR_materials_sheen'}
unsupported = used-allowed
if unsupported:
    raise RuntimeError('Unsupported glTF extensions: '+str(unsupported))
# Count mesh instances too, even if the exporter deduplicates tree meshes.
mesh_triangles = [sum(doc['accessors'][p['indices']]['count']//3 for p in m['primitives'])
                  for m in doc['meshes']]
triangles = sum(mesh_triangles[n['mesh']] for n in doc['nodes'] if 'mesh' in n)
if triangles >= TRIANGLE_LIMIT:
    raise RuntimeError('Triangle budget exceeded')
report = {
    'pass': 3, 'triangles': triangles, 'glb_bytes': output.stat().st_size,
    'extensions': doc.get('extensionsUsed', []), 'render_size': RENDER_SIZE,
    'rest_camera': CAMERAS[0], 'rest_camera_shift': REST_CAMERA_SHIFT,
    'preview_themes': PREVIEW_THEMES,
    'bake': 'Morning sun ON for indirect diffuse bounce + AO on TEXCOORD_1; direct shadows remain live.',
    'runtime_dusk': {'sun_energy': SUN_ENERGY['dusk'], 'sun_color': SUN_COLOR['dusk'],
                     'lamp_watts': LAMP_WATTS['dusk'], 'marble_graze_watts': MARBLE_GRAZE_WATTS['dusk'], 'world_strength': WORLD_STRENGTH['dusk'],
                     'onyx_emissive_strength': ONYX_EMISSION['dusk'],
                     'disc_emissive_strength': LAMP_EMISSION['dusk']},
    'preview_only': 'Onyx area fill is excluded from glTF; runtime uses patterned emission and lamp_* spots.',
}
(work/'report.json').write_text(json.dumps(report, indent=2))
for mood in PREVIEW_THEMES:
    lighting(mood)
    scene.cycles.samples = RENDER_SAMPLES
    scene.render.filepath = str(work/('preview_'+mood+'.png'))
    bpy.ops.render.render(write_still=True)
print('PAVILION_REPORT', json.dumps(report), flush=True)

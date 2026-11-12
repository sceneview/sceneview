"""Cached, public Poly Haven downloads; no API key or third-party Python packages."""
import json
import subprocess
import shutil
from pathlib import Path

ASSETS = [('brown_leather', '1k', ['Diffuse', 'nor_gl', 'Rough'])]
FOLIAGE_ASSET = 'island_tree_03'
HDRIS = {'morning': 'kloofendal_43d_clear_puresky', 'dusk': 'qwantani_dusk_2'}

def fetch(url, path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        subprocess.run(['curl', '-fL', '--retry', '3', '--silent', '--show-error', url, '-o', str(path)+'.part'], check=True)
        Path(str(path)+'.part').replace(path)
    return path

def prepare(work):
    work = Path(work)
    records = []
    for name, res, maps in ASSETS:
        meta = json.loads(fetch('https://api.polyhaven.com/files/'+name, work/(name+'.json')).read_text())
        for channel in maps:
            url = meta[channel][res]['jpg']['url']
            fetch(url, work/(name+'_'+channel+'.jpg'))
            records.append((name, channel+' '+res, url))
    meta = json.loads(fetch('https://api.polyhaven.com/files/'+FOLIAGE_ASSET,
                            work/(FOLIAGE_ASSET+'.json')).read_text())
    for channel in ('diff', 'alpha'):
        url = meta['leaves_'+channel]['1k']['png']['url']
        fetch(url, work/('foliage_'+channel+'.png'))
        records.append((FOLIAGE_ASSET, 'leaves_'+channel+' 1k', url))
    for mood, name in HDRIS.items():
        meta = json.loads(fetch('https://api.polyhaven.com/files/'+name, work/(name+'.json')).read_text())
        url = meta['hdri']['2k']['hdr']['url']
        # Cache by asset ID so changing a sky cannot silently reuse the previous HDR.
        source = fetch(url, work/(name+'_source.hdr'))
        shutil.copyfile(source, work/(mood+'_source.hdr'))
        records.append((name, 'HDRI 2k', url))
    lines = ['# Pavilion asset licences', '', 'All downloaded assets: CC0 1.0, https://polyhaven.com/license and https://creativecommons.org/publicdomain/zero/1.0/.', '',
             'Powered by Poly Haven. Only the leaf colour/alpha maps of Island Tree 03 are used; no downloaded tree geometry. Geometry, chair and split-shell sculpture are original procedural work, released under CC0 1.0.', '',
             'The four photographic textures in textures/ (green marble bookmatch, honey onyx, travertine slab and crimson velvet) were generated with ChatGPT Image (OpenAI image generation, prompted by the maintainer) and are released here under CC0 1.0. Procedural fallback stone, basin tile and gravel graphs are original CC0 work. HDR sources are preserved; build_pavilion.py writes graded morning.hdr and dusk.hdr for Cycles and cmgen.', '', '| Asset | Map | Download |', '|---|---|---|']
    lines += [f'| [{n}](https://polyhaven.com/a/{n}) | {c} | {u} |' for n,c,u in records]
    (Path(__file__).parent/'LICENSES.md').write_text('\n'.join(lines)+'\n')

if __name__ == '__main__':
    import sys
    prepare(sys.argv[1])

"""Area-weighted visible surface sampling in glTF's right-handed Y-up coordinates.
Header <4sIII: b'PAVP', version=1, count, stride=15. Records <fffBBB, no padding.
Colours are sRGB from the morning diffuse bake. Positions are metres.
"""
import struct
import numpy as np
from mathutils import Vector

def write_points(obj, image, path, count):
    from mathutils.bvhtree import BVHTree
    mesh=obj.data; mesh.calc_loop_triangles()
    uv=mesh.uv_layers['Lightmap'].data
    tris=list(mesh.loop_triangles)
    xyz=np.array([[obj.matrix_world @ mesh.vertices[v].co for v in t.vertices] for t in tris])
    areas=np.linalg.norm(np.cross(xyz[:,1]-xyz[:,0],xyz[:,2]-xyz[:,0]),axis=1)/2
    # Keep the reveal concentrated on the place, not the enormous background ground.
    areas[np.max(np.abs(xyz[:,:,:2]),axis=(1,2))>20]=0
    tree=BVHTree.FromPolygons([obj.matrix_world @ v.co for v in mesh.vertices],[list(p.vertices) for p in mesh.polygons])
    rng=np.random.default_rng(2409); pixels=np.array(image.pixels[:]).reshape(image.size[1],image.size[0],4)
    # Front-facing surfaces visible from any catalogue camera; reject interior/hidden faces.
    eyes=[o.location.copy() for o in __import__('bpy').context.scene.objects if o.type=='CAMERA']
    records=[]
    while len(records)<count:
        indices=rng.choice(len(tris),size=8192,p=areas/areas.sum())
        weights=rng.random((8192,2)); s=np.sqrt(weights[:,0]); bary=np.stack([1-s,s*(1-weights[:,1]),s*weights[:,1]],axis=1)
        for ti,w in zip(indices,bary):
            pos=w@xyz[ti]; v=Vector(pos); normal=tris[ti].normal
            visible=False
            for eye in eyes:
                delta=eye-v
                if normal.dot(delta)<=0:continue
                hit=tree.ray_cast(v+normal*.004,delta.normalized(),delta.length-.01)
                if hit[0] is None:visible=True;break
            if not visible:continue
            tex=w@np.array([uv[i].uv[:] for i in tris[ti].loops]); x=int(tex[0]*(image.size[0]-1)); y=int(tex[1]*(image.size[1]-1))
            c=np.clip(pixels[y,x,:3],0,1); c=np.where(c<=.0031308,c*12.92,1.055*c**(1/2.4)-.055)
            records.append(struct.pack('<fffBBB',float(pos[0]),float(pos[2]),float(-pos[1]),*(np.clip(c*255,0,255).astype(int))))
            if len(records)==count:break
    path.write_bytes(struct.pack('<4sIII',b'PAVP',1,count,15)+b''.join(records))
    print('POINTS',len(records),path,flush=True)

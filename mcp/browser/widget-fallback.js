/**
 * WASM-free renderer for hosts whose iframe CSP cannot run Filament.
 * Loaded lazily from the website: no workers, dynamic eval, or decoder downloads.
 * See website-static/js/widget-fallback.README.md for the pinned dependency and reproducible bundle command.
 */
import {
  ACESFilmicToneMapping, AnimationMixer, Box3, CanvasTexture, Clock,
  HemisphereLight, Mesh, MeshBasicMaterial, PerspectiveCamera, PlaneGeometry,
  PMREMGenerator, Scene, SRGBColorSpace, Vector3, WebGLRenderer,
} from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';

function release(root) {
  const textures = new Set();
  const materials = new Set();
  const geometries = new Set();
  root.traverse(node => {
    if (node.geometry) geometries.add(node.geometry);
    for (const material of (Array.isArray(node.material) ? node.material : [node.material])) {
      if (!material) continue;
      materials.add(material);
      for (const value of Object.values(material)) if (value?.isTexture) textures.add(value);
    }
  });
  geometries.forEach(geometry => geometry.dispose());
  materials.forEach(material => material.dispose());
  textures.forEach(texture => { texture.source?.data?.close?.(); texture.dispose(); });
}

async function modelViewer(canvas, url, options = {}) {
  const renderer = new WebGLRenderer({ canvas, antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setClearColor(0, 0);
  renderer.outputColorSpace = SRGBColorSpace;
  renderer.toneMapping = ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.15;
  const scene = new Scene();
  const camera = new PerspectiveCamera(options.fov || 35, 1, 0.01, 1000);
  const controls = new OrbitControls(camera, canvas);
  controls.enableDamping = true;
  controls.enablePan = false;
  controls.autoRotate = options.autoRotate !== false;
  controls.autoRotateSpeed = 0.65;
  controls.maxPolarAngle = Math.PI * 0.49;
  const generator = new PMREMGenerator(renderer);
  const room = new RoomEnvironment();
  const environment = generator.fromScene(room, 0.04);
  room.dispose();
  generator.dispose();
  scene.environment = environment.texture;
  scene.add(new HemisphereLight(0xffffff, 0x526480, 1.5));

  const shadowCanvas = document.createElement('canvas');
  shadowCanvas.width = shadowCanvas.height = 128;
  const context = shadowCanvas.getContext('2d');
  const gradient = context.createRadialGradient(64, 64, 5, 64, 64, 64);
  gradient.addColorStop(0, 'rgba(0,0,0,0.55)');
  gradient.addColorStop(1, 'rgba(0,0,0,0)');
  context.fillStyle = gradient;
  context.fillRect(0, 0, 128, 128);
  const shadow = new Mesh(new PlaneGeometry(1, 1), new MeshBasicMaterial({
    map: new CanvasTexture(shadowCanvas), transparent: true, depthWrite: false,
  }));
  shadow.rotation.x = -Math.PI / 2;
  scene.add(shadow);
  const clock = new Clock();
  let model, mixer, disposed = false, generation = 0;
  const size = new Vector3();
  const center = new Vector3();
  function resize() {
    const width = Math.max(1, canvas.clientWidth);
    const height = Math.max(1, canvas.clientHeight);
    renderer.setSize(width, height, false);
    camera.aspect = width / height;
    camera.updateProjectionMatrix();
  }
  function frameModel({ fill = 0.8 } = {}) {
    if (!model) return;
    const halfFov = camera.fov * Math.PI / 360;
    const sweep = Math.hypot(size.x, size.z) / 2;
    const tangent = fill * Math.tan(halfFov);
    const distance = Math.max(sweep + size.y / (2 * tangent), sweep * Math.sqrt(1 + 1 / (tangent * camera.aspect) ** 2));
    camera.position.set(Math.sin(0.6) * distance, size.y / 2 + distance * 0.12, Math.cos(0.6) * distance);
    controls.update();
  }
  const observer = new ResizeObserver(resize);
  observer.observe(canvas);
  resize();
  renderer.setAnimationLoop(() => {
    if (disposed || document.hidden) return;
    const delta = Math.min(clock.getDelta(), 0.1);
    mixer?.update(delta);
    controls.update(delta);
    renderer.render(scene, camera);
  });
  const viewer = {
    async loadModel(modelUrl) {
      const current = ++generation;
      // No Draco, KTX2 or meshopt decoder is installed: those may require WASM.
      // GLTFLoader rejects unsupported required compression instead of drawing garbage.
      const gltf = await new GLTFLoader().loadAsync(modelUrl);
      if (disposed || current !== generation) {
        release(gltf.scene);
        throw new DOMException('Model load superseded', 'AbortError');
      }
      const bounds = new Box3().setFromObject(gltf.scene);
      bounds.getSize(size);
      bounds.getCenter(center);
      const extent = Math.max(size.x, size.y, size.z);
      if (!Number.isFinite(extent) || extent <= 0) {
        release(gltf.scene);
        throw new Error('This file has no visible 3D geometry.');
      }
      if (model) { scene.remove(model); release(model); mixer?.stopAllAction(); }
      model = gltf.scene;
      model.position.sub(center);
      model.position.y += size.y / 2;
      scene.add(model);
      controls.target.set(0, size.y / 2, 0);
      frameModel();
      camera.near = Math.max(extent / 1000, 0.0001);
      camera.far = extent * 100;
      camera.updateProjectionMatrix();
      controls.minDistance = extent * 0.25;
      controls.maxDistance = extent * 10;
      controls.update();
      shadow.scale.set(Math.max(size.x, extent * 0.25) * 1.7, Math.max(size.z, extent * 0.25) * 1.7, 1);
      shadow.position.y = -extent * 0.002;
      mixer = new AnimationMixer(model);
      for (const clip of gltf.animations) mixer.clipAction(clip).play();
      renderer.render(scene, camera);
    },
    getModelSize() { return size.toArray(); },
    frameModel,
    setAutoRotate(enabled) { controls.autoRotate = enabled; },
    dispose() {
      if (disposed) return;
      disposed = true;
      generation++;
      observer.disconnect();
      renderer.setAnimationLoop(null);
      controls.dispose();
      mixer?.stopAllAction();
      if (model) release(model);
      release(shadow);
      environment.dispose();
      renderer.dispose();
      renderer.forceContextLoss();
    },
  };
  try { await viewer.loadModel(url); return viewer; }
  catch (error) { viewer.dispose(); throw error; }
}

window.SceneViewFallback = { modelViewer };

/** Spatial answer v1: metres, Y-up, box dimensions in width/height/depth order. */
export interface SpatialAnswerSpec {
  v: 1;
  object: { type: "box"; sizeM: [number, number, number] };
  dimensionSource: "user" | "estimated" | "measured";
  placement: "floor" | "table";
  label: string;
}

export interface SpatialAnswerInput {
  widthCm: unknown;
  heightCm: unknown;
  depthCm: unknown;
  label: unknown;
  placement?: unknown;
  dimensionSource?: unknown;
}

function validateSpec(spec: SpatialAnswerSpec): void {
  if (spec.v !== 1) throw new Error("Spatial answer v must be 1.");
  if (
    spec.object?.type !== "box" ||
    !Array.isArray(spec.object.sizeM) ||
    spec.object.sizeM.length !== 3 ||
    spec.object.sizeM.some(
      (s) => typeof s !== "number" || !Number.isFinite(s) || s <= 0.01 || s > 10
    )
  ) {
    throw new Error(
      "Each box size must be a finite number greater than 0.01 and at most 10 metres (greater than 1 and at most 1000 cm)."
    );
  }
  if (typeof spec.label !== "string" || Array.from(spec.label).length > 60) {
    throw new Error("label must be a string of at most 60 characters.");
  }
  if (spec.placement !== "floor" && spec.placement !== "table") {
    throw new Error('placement must be "floor" or "table".');
  }
  if (!["user", "estimated", "measured"].includes(spec.dimensionSource)) {
    throw new Error('dimensionSource must be "user", "estimated" or "measured".');
  }
}

/** Convert centimetres supplied by the assistant into the shared app spec. */
export function buildSpatialAnswerSpec(input: SpatialAnswerInput): SpatialAnswerSpec {
  const sizes = [input.widthCm, input.heightCm, input.depthCm];
  if (sizes.some((s) => typeof s !== "number" || !Number.isFinite(s) || s <= 1 || s > 1000)) {
    throw new Error(
      "widthCm, heightCm and depthCm must each be a finite number greater than 1 and at most 1000 cm."
    );
  }
  const dimensionSource = input.dimensionSource === undefined ? "user" : input.dimensionSource;
  if (dimensionSource !== "user" && dimensionSource !== "estimated") {
    throw new Error('dimensionSource must be "user" or "estimated".');
  }
  const spec: SpatialAnswerSpec = {
    v: 1,
    object: {
      type: "box",
      sizeM: sizes.map((s) => (s as number) / 100) as [number, number, number],
    },
    dimensionSource,
    placement: (input.placement === undefined
      ? "floor"
      : input.placement) as SpatialAnswerSpec["placement"],
    label: input.label as string,
  };
  validateSpec(spec);
  return spec;
}

/** UTF-8 JSON, base64url without padding; enforce the app's payload limit. */
export function encodeSpatialAnswer(spec: SpatialAnswerSpec): string {
  validateSpec(spec);
  const encoded = Buffer.from(JSON.stringify(spec), "utf8").toString("base64url");
  if (encoded.length > 2048)
    throw new Error("Encoded spatial answer payload must be at most 2048 characters.");
  return encoded;
}

export function spatialAnswerUrl(spec: SpatialAnswerSpec): string {
  return `https://sceneview.github.io/open?demo=spatial-answer&answer=${encodeSpatialAnswer(spec)}`;
}

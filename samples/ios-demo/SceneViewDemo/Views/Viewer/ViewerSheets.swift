import SwiftUI
import SceneViewSwift

/// Model-viewer sheets and bars — the iOS twins of Android's
/// `ui/viewer/{ModelPickerSheet,EnvironmentSheet,AnimationBar}.kt`.

/// A bundled USDZ the viewer can show. `assetName` is the bundle resource
/// name without extension and the key of its `model_thumb_<assetName>` image.
struct BundledViewerModel: Identifiable, Equatable {
    let assetName: String
    let displayName: String
    /// One line under the name in the picker, saying what the model shows —
    /// Android's `demo_model_desc_*` strings, verbatim where the model is shared.
    var description: String? = nil
    /// HD pack asset id (`assets/hd-pack/ios.json`) when the model is not in
    /// the bundle but downloaded once; `nil` for a bundled USDZ.
    var hdPackID: String? = nil
    /// The bundled USDZ shown instantly while the HD asset is not on disk.
    /// `nil` for an HD-only model (Museum & Space): its thumbnail stands in.
    var standInAssetName: String? = nil
    /// Turn about +Y, degrees, applied when the model is put on stage —
    /// Android's `frontYaw`, same sign (right-handed, counter-clockwise seen
    /// from above). The camera opens head-on (``ModelViewerDemo/openingAzimuth``);
    /// a model that reads better three-quarter carries its own turn here.
    var frontYaw: Float = 0
    /// `false` for a model shown static: its clips stay listed under Animate
    /// but do not start on their own (Perseverance ships 23 rigging clips).
    var autoplaysAnimations: Bool = true
    var id: String { assetName }

    /// No bundled USDZ at all: the model exists only once the HD pack has it.
    var isHDOnly: Bool { hdPackID != nil && standInAssetName == nil }

    /// The bundled resource to load when the HD asset is not available.
    /// `nil` for an HD-only model.
    var bundledResourceName: String? { isHDOnly ? nil : (standInAssetName ?? assetName) }

    /// What "View in AR" places. Always a bundled USDZ: the HD file is not
    /// used in AR until a real-device run proves it (follow-up to #4147).
    /// `nil` for an HD-only model, which "View in AR" does not offer.
    var arResourceName: String? { bundledResourceName }

    /// Asset-catalog thumbnail, or `nil` when none was generated for this model.
    var thumbnailName: String? {
        let name = "model_thumb_\(assetName)"
        #if canImport(UIKit)
        return UIImage(named: name) == nil ? nil : name
        #else
        return nil
        #endif
    }
}

/// A bundled HDR the viewer can light with. `assetName` is the `.hdr` resource
/// name without extension and the key of its `env_thumb_<assetName>` image.
struct ViewerEnvironment: Identifiable, Equatable {
    let assetName: String
    let displayName: String

    var id: String { assetName }

    var thumbnailName: String? {
        let name = "env_thumb_\(assetName)"
        #if canImport(UIKit)
        return UIImage(named: name) == nil ? nil : name
        #else
        return nil
        #endif
    }
}

// MARK: - Models sheet

struct ModelPickerSheet: View {
    let models: [BundledViewerModel]
    /// The "Museum & Space" section: HD pack models with no bundled copy.
    var museum: [BundledViewerModel] = []
    let selected: BundledViewerModel
    let surpriseAvailable: Bool
    let surpriseLoading: Bool
    let onSelect: (BundledViewerModel) -> Void
    let onSurprise: () -> Void
    let onBrowse: () -> Void

    /// Two cards per row, as on Android; three on a regular-width screen (iPad).
    @Environment(\.horizontalSizeClass) private var sizeClass
    private var columnCount: Int { sizeClass == .regular ? 3 : 2 }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
                Text("Models")
                    .font(SceneViewTokens.TypeScale.title)
                    .tracking(SceneViewTokens.TypeScale.titleTracking)
                    .padding(.horizontal, SceneViewTokens.Space.md)

                if surpriseAvailable {
                    // Promoted above the grid in *both* themes: #3585 is about the
                    // action being buried under the list, which it was in light too.
                    // DESIGN.md primary-light/container tint ties this action to
                    // the selected chips; primary glyph + outline signal a button.
                    Button(action: onSurprise) {
                        HStack(spacing: SceneViewTokens.Space.md) {
                            SurpriseShuffleIcon(loading: surpriseLoading)
                                .foregroundStyle(SceneViewTokens.HomeColor.primary)
                                .tint(SceneViewTokens.HomeColor.primary)
                            VStack(alignment: .leading, spacing: SceneViewTokens.Space.xs) {
                                Text("Surprise me")
                                    .font(SceneViewTokens.TypeScale.card)
                                    .foregroundStyle(SceneViewTokens.HomeColor.primary)
                                // Keep the copy and its wrapping stable while the icon spins.
                                Text("A random CC-BY model from Sketchfab")
                                    .font(SceneViewTokens.TypeScale.captionRegular)
                                    .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .frame(minHeight: SceneViewTokens.Layout.touchTarget)
                        .padding(SceneViewTokens.Space.md)
                        .background(SceneViewTokens.HomeColor.primaryContainer,
                                    in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous))
                        .overlay {
                            RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous)
                                .strokeBorder(SceneViewTokens.HomeColor.primary,
                                              lineWidth: SceneViewTokens.Home.cardOutlineWidth)
                        }
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .disabled(surpriseLoading)
                    .accessibilityLabel("Surprise me")
                    .accessibilityValue(surpriseLoading ? "Loading" : "Ready")
                    .accessibilityHint("Loads a random CC-BY model from Sketchfab")
                    .accessibilityIdentifier("model-picker-surprise")
                    .padding(.horizontal, SceneViewTokens.Space.md)
                }

                modelGrid(models)

                if !museum.isEmpty {
                    // HD pack models with no bundled copy, same card as the
                    // grid above; Android's picker has the same section, same order.
                    Text("Museum & Space")
                        .font(SceneViewTokens.TypeScale.card)
                        .foregroundStyle(.primary)
                        .padding(.horizontal, SceneViewTokens.Space.md)
                        .padding(.top, SceneViewTokens.Space.sm)
                        .accessibilityAddTraits(.isHeader)
                    modelGrid(museum)
                }

                ViewerSheetRow(title: "Browse online models…", subtitle: nil, loading: false, action: onBrowse)
            }
            .padding(.vertical, SceneViewTokens.Space.md)
        }
    }

    /// Cards in rows of ``columnCount``. A `Grid`, not a `LazyVGrid`: a row
    /// takes the height of its tallest card, so a description that wraps to a
    /// second line grows both cards of the row — Android's `CardRow`.
    private func modelGrid(_ items: [BundledViewerModel]) -> some View {
        let rows = stride(from: 0, to: items.count, by: columnCount).map {
            Array(items[$0 ..< min($0 + columnCount, items.count)])
        }
        return Grid(horizontalSpacing: SceneViewTokens.Space.sm, verticalSpacing: SceneViewTokens.Space.sm) {
            ForEach(rows, id: \.first?.id) { row in
                GridRow(alignment: .top) {
                    ForEach(row) { model in
                        ModelPickerCard(model: model, selected: model == selected) { onSelect(model) }
                    }
                    // A lone last card keeps its column width.
                    ForEach(row.count ..< columnCount, id: \.self) { _ in
                        Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
                    }
                }
            }
        }
        .padding(.horizontal, SceneViewTokens.Space.md)
    }
}

/// One model in the picker — Android's `PickerCard`: 5:4 media over a
/// `card` title and a two-line description, on `surface-container-high` with
/// the 1 pt `outline-subtle` hairline; the model on screen gets the 2 pt
/// `primary` outline instead.
///
/// The media is the `model_thumb_*` render: the model alone on a transparent
/// 600×480 (5:4, `mediaAspect`) canvas, so it sits straight on the card's own
/// `surface-container-high` fill in both themes, as on Android. The files are
/// Android's `model_thumb_*.webp` re-encoded as HEIC with alpha; the two
/// iOS-only models (Cyberpunk Hovercar, Butterfly) are rendered the same way
/// from their USDZ (see `tools/demo-previews/README.md`).
struct ModelPickerCard: View {
    let model: BundledViewerModel
    let selected: Bool
    let action: () -> Void

    // Card text follows Dynamic Type like the Home cards (`DemoMediaCard`):
    // the token sizes are the default-size values, scaled with the text style
    // of the same size.
    @ScaledMetric(relativeTo: .headline) private var titleSize = SceneViewTokens.TypeScale.cardSize
    @ScaledMetric(relativeTo: .footnote) private var captionSize = SceneViewTokens.TypeScale.captionSize
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var shape: RoundedRectangle {
        RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous)
    }

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                ZStack {
                    if let thumb = model.thumbnailName {
                        Image(thumb).resizable().scaledToFit()
                    } else {
                        Image(systemName: "cube.transparent")
                            .font(.title2)
                            .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceFaint)
                    }
                }
                .frame(maxWidth: .infinity)
                .aspectRatio(SceneViewTokens.Layout.mediaAspect, contentMode: .fit)
                .clipped()

                VStack(alignment: .leading, spacing: SceneViewTokens.Space.xs) {
                    // The full name, never "Apollo 11 Comma…": at the default
                    // size it is 219 pt against ~150 pt of text on a phone, too
                    // long for one line even at 0.85, so the title wraps to a
                    // second line and only shrinks past that.
                    Text(model.displayName)
                        .font(.system(size: titleSize, weight: .semibold))
                        .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? 3 : 2)
                        .minimumScaleFactor(0.85)
                        .truncationMode(.tail)
                        .fixedSize(horizontal: false, vertical: true)
                    if let description = model.description {
                        Text(description)
                            .font(.system(size: captionSize, weight: .regular))
                            .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                            .lineLimit(dynamicTypeSize.isAccessibilitySize ? 4 : 2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.top, SceneViewTokens.Home.cardTextPaddingTop)
                .padding(.horizontal, SceneViewTokens.Home.cardTextPaddingHorizontal)
                .padding(.bottom, SceneViewTokens.Home.cardTextPaddingBottom)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(SceneViewTokens.HomeColor.surfaceContainerHigh)
            .clipShape(shape)
            .overlay {
                shape.strokeBorder(
                    selected ? SceneViewTokens.HomeColor.primary : SceneViewTokens.HomeColor.outlineSubtle,
                    lineWidth: selected ? SceneViewTokens.Layout.selectedOutlineWidth
                                        : SceneViewTokens.Home.cardOutlineWidth
                )
            }
            .contentShape(shape)
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(model.displayName)
        .accessibilityValue(model.description ?? "")
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}

/// `shuffle` describes switching to an unpredictable next model more directly
/// than decorative sparkles. Shared by the dark sheet action and viewer pill.
/// The symbol slot and text stay in place throughout loading, without a layout jump.
struct SurpriseShuffleIcon: View {
    let loading: Bool

    var body: some View {
        Image(systemName: "shuffle")
            .font(.system(size: SceneViewTokens.Layout.dockIconSize, weight: .semibold))
            .opacity(loading ? 0 : 1)
            .frame(width: SceneViewTokens.Layout.dockIconSize, height: SceneViewTokens.Layout.dockIconSize)
            .overlay {
                if loading {
                    ProgressView().controlSize(.small)
                }
            }
            .accessibilityHidden(true)
    }
}

private struct ViewerSheetRow: View {
    let title: String
    let subtitle: String?
    let loading: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(SceneViewTokens.TypeScale.body).foregroundStyle(.primary)
                    if let subtitle {
                        Text(subtitle).font(SceneViewTokens.TypeScale.captionRegular).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if loading {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
                }
            }
            .padding(.horizontal, SceneViewTokens.Space.md)
            .padding(.vertical, SceneViewTokens.Space.sm + 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(loading)
        .accessibilityLabel(title)
    }
}

// MARK: - Environment sheet

struct EnvironmentSheet: View {
    let environments: [ViewerEnvironment]
    let selected: ViewerEnvironment
    @Binding var intensity: Float
    @Binding var showSkybox: Bool
    let onSelect: (ViewerEnvironment) -> Void
    let onReset: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
                Text("Environment")
                    .font(SceneViewTokens.TypeScale.title)
                    .tracking(SceneViewTokens.TypeScale.titleTracking)
                    .padding(.horizontal, SceneViewTokens.Space.md)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: SceneViewTokens.Space.sm) {
                        ForEach(environments) { env in
                            Button { onSelect(env) } label: {
                                VStack(alignment: .center, spacing: SceneViewTokens.Space.xs) {
                                    ZStack {
                                        SceneViewTokens.HomeColor.chipBackground
                                        if let thumb = env.thumbnailName {
                                            Image(thumb).resizable().scaledToFill()
                                        } else {
                                            Image(systemName: "sun.max").foregroundStyle(.secondary)
                                        }
                                    }
                                    .frame(width: SceneViewTokens.Layout.viewerEnvironmentTile,
                                           height: SceneViewTokens.Layout.viewerEnvironmentTile)
                                    .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous)
                                            .strokeBorder(SceneViewTheme.primary,
                                                          lineWidth: env == selected ? SceneViewTokens.Layout.selectedOutlineWidth : 0)
                                    )
                                    // Two lines + a small scale floor so every bundled
                                    // name ("Outdoor Cloudy", "Rooftop Night") reads fully.
                                    Text(env.displayName)
                                        .font(SceneViewTokens.TypeScale.captionRegular)
                                        .foregroundStyle(.primary)
                                        .multilineTextAlignment(.center)
                                        .lineLimit(2)
                                        .minimumScaleFactor(0.85)
                                        .frame(width: SceneViewTokens.Layout.viewerEnvironmentTile + 16)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            .buttonStyle(PressScaleButtonStyle())
                            .accessibilityLabel(env.displayName)
                            .accessibilityAddTraits(env == selected ? .isSelected : [])
                        }
                    }
                    .padding(.horizontal, SceneViewTokens.Space.md)
                }

                VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
                    LabeledSlider(label: "IBL intensity", value: $intensity, range: 0...2,
                                  valueText: String(format: "%.1f×", intensity))
                    Toggle("Show environment", isOn: $showSkybox)
                        .font(SceneViewTokens.TypeScale.body)
                    Button("Reset lighting", action: onReset)
                        .font(SceneViewTokens.TypeScale.bodyMedium)
                        .tint(SceneViewTheme.primary)
                }
                .padding(.horizontal, SceneViewTokens.Space.md)
            }
            .padding(.vertical, SceneViewTokens.Space.md)
        }
    }
}

// MARK: - Animation bar

/// Floating playback bar above the dock: play/pause, clip picker, progress.
struct AnimationBar: View {
    let clipNames: [String]
    @Binding var selectedClip: Int
    @Binding var playing: Bool
    @Binding var progress: Float
    let onScrub: (Float) -> Void

    var body: some View {
        HStack(spacing: SceneViewTokens.Space.sm) {
            Button {
                playing.toggle()
            } label: {
                Image(systemName: playing ? "pause.fill" : "play.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(SceneViewTokens.Glass.onGlass)
                    .frame(width: SceneViewTokens.Layout.viewerAnimationButton,
                           height: SceneViewTokens.Layout.viewerAnimationButton)
            }
            .buttonStyle(PressScaleButtonStyle(scale: SceneViewTokens.Spring.chromePressScale))
            .accessibilityLabel(playing ? "Pause" : "Play")

            Menu {
                ForEach(Array(clipNames.enumerated()), id: \.offset) { index, name in
                    Button(name) { selectedClip = index }
                }
            } label: {
                Text(clipNames.indices.contains(selectedClip) ? clipNames[selectedClip] : "Clip \(selectedClip + 1)")
                    .font(SceneViewTokens.TypeScale.caption)
                    .foregroundStyle(SceneViewTokens.Glass.onGlass)
                    .lineLimit(1)
                    .padding(.horizontal, SceneViewTokens.Space.sm)
            }

            Slider(value: Binding(get: { progress }, set: { onScrub($0) }), in: 0...1)
                .tint(SceneViewTokens.Glass.onGlass)
                .accessibilityLabel("Animation progress")
        }
        .padding(.horizontal, SceneViewTokens.Space.sm)
        .padding(.vertical, SceneViewTokens.Space.xs)
        .glassBackground(in: Capsule())
    }
}

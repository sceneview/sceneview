import SwiftUI
import SceneViewSwift

/// Model-viewer sheets and bars — the iOS twins of Android's
/// `ui/viewer/{ModelPickerSheet,EnvironmentSheet,AnimationBar}.kt`.

/// A bundled USDZ the viewer can show. `assetName` is the bundle resource
/// name without extension and the key of its `model_thumb_<assetName>` image.
struct BundledViewerModel: Identifiable, Equatable {
    let assetName: String
    let displayName: String
    var id: String { assetName }

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

    /// Was this HDR authored as a *place* you could stand in, or as a lighting rig?
    ///
    /// The distinction is visible in the `env_thumb_*` chrome balls: `studio` and
    /// `studio_warm` reflect softbox panels — they are light sources, and drawing
    /// their skybox puts the model in a grey equipment room. `sunset`,
    /// `outdoor_cloudy`, `night_sky` and `rooftop_night` reflect a horizon, a field,
    /// the Milky Way and a city skyline — hiding those throws away the thing you
    /// just picked, which is what #3583 is about.
    ///
    /// This drives the *default* backdrop state only. An explicit toggle by the user
    /// always wins and is remembered — see `skyboxOverride` in `ModelViewerDemo`.
    let authoredAsPlace: Bool

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
    let selected: BundledViewerModel
    let surpriseAvailable: Bool
    let surpriseLoading: Bool
    let onSelect: (BundledViewerModel) -> Void
    let onSurprise: () -> Void
    let onBrowse: () -> Void

    private let columns = [GridItem(.adaptive(minimum: 140), spacing: SceneViewTokens.Space.sm)]

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

                LazyVGrid(columns: columns, spacing: SceneViewTokens.Space.sm) {
                    ForEach(models) { model in
                        Button {
                            onSelect(model)
                        } label: {
                            VStack(alignment: .leading, spacing: SceneViewTokens.Space.xs) {
                                ZStack {
                                    SceneViewTokens.HomeColor.chipBackground
                                    if let thumb = model.thumbnailName {
                                        Image(thumb).resizable().scaledToFill()
                                    } else {
                                        Image(systemName: "cube.transparent")
                                            .font(.title2)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                .aspectRatio(1, contentMode: .fit)
                                .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.sm, style: .continuous))
                                Text(model.displayName)
                                    .font(SceneViewTokens.TypeScale.caption)
                                    .foregroundStyle(.primary)
                                    .lineLimit(1)
                            }
                            .padding(SceneViewTokens.Space.sm)
                            .background(
                                RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous)
                                    .strokeBorder(SceneViewTheme.primary,
                                                  lineWidth: model == selected ? SceneViewTokens.Layout.selectedOutlineWidth : 0)
                            )
                        }
                        .buttonStyle(PressScaleButtonStyle())
                        .accessibilityLabel(model.displayName)
                        .accessibilityAddTraits(model == selected ? .isSelected : [])
                    }
                }
                .padding(.horizontal, SceneViewTokens.Space.md)

                ViewerSheetRow(title: "Browse online models…", subtitle: nil, loading: false, action: onBrowse)
            }
            .padding(.vertical, SceneViewTokens.Space.md)
        }
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
        .background(glassBackground(in: Capsule()))
    }
}

import SwiftUI
import SceneViewSwift

/// iOS counterpart of Android's `DemoScaffold` settings sheet.
///
/// Mirrors the Android Material 3 ModalBottomSheet pattern (see
/// `samples/android-demo/.../DemoScaffold.kt`) so the two store apps surface their
/// per-demo controls the same way — strict iOS V1 == Android subset rule from
/// `feedback_ios_mirror_android.md`.
///
/// Usage:
/// ```swift
/// var body: some View {
///     ZStack {
///         SceneView { ... }.ignoresSafeArea()
///     }
///     .demoSettingsSheet {
///         // any SwiftUI controls — sliders, pickers, toggles…
///         VStack { Slider(value: $value) }
///     }
/// }
/// ```
///
/// Behaviour:
/// - Floating "gear" button anchored bottom-trailing.
/// - Peek chip ("Settings") sits to the left of the button (8pt gap, never
///   overlapping) while the sheet is closed.
/// - Tap the chip OR the button to open the sheet at the medium detent. The
///   sheet supports `.fraction(0.25)`, `.medium`, and `.large` detents and a
///   visible drag-indicator handle. Scene keeps tracking 6DOF (AR) while the
///   sheet is at the partial detent thanks to
///   `.presentationBackgroundInteraction(.enabled)`.
/// - `.presentationBackground(.ultraThinMaterial)` matches Liquid Glass.
///
/// Empty-controls demos can simply omit the modifier — there is no FAB-hidden
/// state to manage.
public struct DemoSettingsSheetModifier<SheetContent: View>: ViewModifier {
    @State private var isPresented: Bool = false
    private let sheetContent: () -> SheetContent

    public init(@ViewBuilder content: @escaping () -> SheetContent) {
        self.sheetContent = content
    }

    public func body(content: Content) -> some View {
        content
            .overlay(alignment: .bottomTrailing) {
                // Lay the peek chip fully to the LEFT of the circular FAB with
                // an 8pt gap so the two never overlap — see #1374. (The previous
                // right-aligned VStack let the chip's rounded corners tuck
                // behind the FAB.)
                HStack(alignment: .center, spacing: 8) {
                    if !isPresented {
                        Button {
                            #if os(iOS)
                            SceneViewHaptic.shared.selection()
                            #endif
                            isPresented = true
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "slider.horizontal.3")
                                    .font(.caption2)
                                Text("Settings")
                                    .font(.caption2)
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(.ultraThinMaterial)
                            .clipShape(Capsule())
                            .foregroundStyle(.primary)
                        }
                        .accessibilityLabel("Open demo settings")
                        .accessibilityIdentifier("demo-settings-peek")
                    }

                    Button {
                        #if os(iOS)
                        SceneViewHaptic.shared.selection()
                        #endif
                        isPresented = true
                    } label: {
                        Image(systemName: "slider.horizontal.3")
                            .font(.title3)
                            .padding(14)
                            .background(.ultraThinMaterial, in: Circle())
                            .foregroundStyle(.primary)
                            .shadow(color: .black.opacity(0.25), radius: 6, x: 0, y: 2)
                    }
                    .accessibilityLabel("Demo settings")
                    .accessibilityIdentifier("demo-settings-fab")
                }
                .padding(16)
            }
            .sheet(isPresented: $isPresented) {
                DemoSettingsContainer {
                    sheetContent()
                }
                .presentationDetents([.fraction(0.25), .medium, .large])
                .presentationDragIndicator(.visible)
                #if os(iOS)
                .presentationBackgroundInteraction(.enabled)
                .presentationContentInteraction(.scrolls)
                .presentationBackground(.ultraThinMaterial)
                #endif
            }
    }
}

/// Padded, scrollable container so the sheet content survives long control
/// stacks (mirrors Android `Column { verticalScroll(...) }` inside the
/// ModalBottomSheet body).
public struct DemoSettingsContainer<Content: View>: View {
    let content: () -> Content

    public init(@ViewBuilder content: @escaping () -> Content) {
        self.content = content
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                content()
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
            .padding(.top, 8)
        }
    }
}

public extension View {
    /// Wraps the scene in the SceneView demo settings sheet.
    func demoSettingsSheet<SheetContent: View>(
        @ViewBuilder content: @escaping () -> SheetContent
    ) -> some View {
        modifier(DemoSettingsSheetModifier(content: content))
    }
}

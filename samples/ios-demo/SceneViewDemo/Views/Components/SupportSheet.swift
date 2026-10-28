import StoreKit
import SwiftUI

/// "Support SceneView": the honest pitch, three one-time tips, and the way out.
///
/// One sheet for both doors: the About card (`.about`) and the occasional prompt
/// (`.prompt`, which adds "Not now" and "Don't ask again"). A tap on an amount opens
/// Apple's own purchase sheet straight away; once it goes through, the sheet says
/// thank you. The amounts and the way out sit in the lower half, under the thumb.
struct SupportSheet: View {
    let presentation: SupportStore.Presentation
    let onClose: () -> Void

    @ObservedObject private var store = SupportStore.shared
    @State private var contentHeight: CGFloat = 420

    var body: some View {
        Group {
            if store.purchaseState == .thanked {
                thanks
            } else {
                offer
            }
        }
        .padding(.horizontal, SceneViewTokens.Space.lg)
        .padding(.top, SceneViewTokens.Space.lg)
        .padding(.bottom, SceneViewTokens.Space.sm)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
        .frame(maxHeight: .infinity, alignment: .top)
        #if os(macOS)
        .frame(width: 440)
        #endif
        .presentationDetents([.height(contentHeight)])
        .presentationDragIndicator(.visible)
        .presentationBackground(SceneViewTokens.HomeColor.surfaceContainer)
        .sensoryFeedback(.success, trigger: store.purchaseState == .thanked)
        .task { await store.loadProducts() }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("support-sheet")
    }

    // MARK: Offer

    private var offer: some View {
        VStack(spacing: SceneViewTokens.Space.md) {
            badge(systemName: "heart.fill")

            VStack(spacing: SceneViewTokens.Space.sm) {
                Text("Support SceneView")
                    .font(SceneViewTokens.TypeScale.title)
                    .tracking(SceneViewTokens.TypeScale.titleTracking)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                Text("SceneView is open source and maintained by volunteers. It is funded by the people who use it: if it is useful to you, a one-time tip helps keep it going.")
                    .font(SceneViewTokens.TypeScale.body)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }

            amounts
                .padding(.top, SceneViewTokens.Space.xs)

            status

            if presentation == .prompt {
                HStack(spacing: SceneViewTokens.Space.sm) {
                    secondaryButton("Not now", id: "support-not-now", action: onClose)
                    secondaryButton("Don't ask again", id: "support-never-ask") {
                        store.neverAskAgain()
                        onClose()
                    }
                }
            } else {
                secondaryButton("Close", id: "support-close", action: onClose)
            }
        }
    }

    @ViewBuilder
    private var amounts: some View {
        switch store.loadState {
        case .loading:
            HStack(spacing: SceneViewTokens.Space.sm) {
                ForEach(0..<3, id: \.self) { _ in
                    RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous)
                        .fill(SceneViewTokens.HomeColor.secondaryContainer)
                        .frame(height: Self.amountHeight)
                }
            }
            .overlay { ProgressView() }
            .accessibilityLabel("Loading amounts")
        case .unavailable:
            VStack(spacing: SceneViewTokens.Space.xs) {
                Text("The App Store can't be reached right now.")
                    .font(SceneViewTokens.TypeScale.captionRegular)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                    .multilineTextAlignment(.center)
                secondaryButton("Try again", id: "support-retry") {
                    Task { await store.loadProducts() }
                }
            }
        case .loaded:
            HStack(spacing: SceneViewTokens.Space.sm) {
                ForEach(Array(store.products.enumerated()), id: \.element.id) { index, product in
                    amountButton(product, label: Self.tierLabels[min(index, 2)],
                                 emphasised: index == 1)
                }
            }
        }
    }

    @ViewBuilder
    private var status: some View {
        switch store.purchaseState {
        case .pending:
            note("Waiting for approval. Thank you for asking.")
        case .failed:
            note("The purchase did not go through. You can try again.")
        default:
            note("A one-time purchase. It unlocks nothing: every feature stays free.")
        }
    }

    private static let tierLabels = ["Small", "Medium", "Large"]
    private static let amountHeight: CGFloat = 64

    private func amountButton(_ product: Product, label: String, emphasised: Bool) -> some View {
        let busy = store.purchaseState == .purchasing(product.id)
        let fill = emphasised ? SceneViewTokens.HomeColor.primary : SceneViewTokens.HomeColor.secondaryContainer
        let ink = emphasised ? SceneViewTokens.HomeColor.onPrimary : SceneViewTokens.HomeColor.onSecondaryContainer
        let shape = RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous)
        return Button {
            Task { await store.purchase(product) }
        } label: {
            VStack(spacing: 2) {
                if busy {
                    ProgressView().tint(ink)
                } else {
                    Text(product.displayPrice)
                        .font(SceneViewTokens.TypeScale.card)
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    Text(label)
                        .font(SceneViewTokens.TypeScale.captionRegular)
                        .opacity(0.85)
                }
            }
            .foregroundStyle(ink)
            .frame(maxWidth: .infinity, minHeight: Self.amountHeight)
            .background(fill, in: shape)
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .disabled(store.purchaseState.isPurchasing)
        .accessibilityLabel("Tip \(product.displayPrice)")
        .accessibilityIdentifier("support-tier-\(label.lowercased())")
    }

    // MARK: Thanks

    private var thanks: some View {
        VStack(spacing: SceneViewTokens.Space.md) {
            badge(systemName: "heart.circle.fill")
            VStack(spacing: SceneViewTokens.Space.sm) {
                Text("Thank you!")
                    .font(SceneViewTokens.TypeScale.title)
                    .tracking(SceneViewTokens.TypeScale.titleTracking)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                    .accessibilityAddTraits(.isHeader)
                Text("Your support keeps SceneView open source and maintained, for everyone who builds with it.")
                    .font(SceneViewTokens.TypeScale.body)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button(action: onClose) {
                Text("Done")
                    .font(SceneViewTokens.TypeScale.bodySemibold)
                    .foregroundStyle(SceneViewTokens.HomeColor.onPrimary)
                    .frame(maxWidth: .infinity, minHeight: 50)
                    .background(SceneViewTokens.HomeColor.primary, in: Capsule())
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .padding(.top, SceneViewTokens.Space.xs)
            .accessibilityIdentifier("support-done")
        }
    }

    // MARK: Parts

    private func badge(systemName: String) -> some View {
        ZStack {
            Circle().fill(SceneViewTokens.HomeColor.primary.opacity(0.16))
            Image(systemName: systemName)
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(SceneViewTokens.HomeColor.primary)
        }
        .frame(width: 60, height: 60)
        .accessibilityHidden(true)
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .font(SceneViewTokens.TypeScale.captionRegular)
            .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func secondaryButton(_ title: String, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(SceneViewTokens.TypeScale.bodySemibold)
                .foregroundStyle(SceneViewTokens.HomeColor.primary)
                .frame(maxWidth: .infinity, minHeight: SceneViewTokens.Layout.touchTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
    }
}

private extension SupportStore.PurchaseState {
    var isPurchasing: Bool {
        if case .purchasing = self { return true }
        return false
    }
}

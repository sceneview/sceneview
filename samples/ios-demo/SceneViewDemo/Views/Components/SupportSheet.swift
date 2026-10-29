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
    @State private var analytics: SupportPromptAnalyticsSession
    @State private var contentHeight: CGFloat = 420
    @AccessibilityFocusState private var focus: Focus?

    private enum Focus: Hashable { case thanks, status }

    init(presentation: SupportStore.Presentation, onClose: @escaping () -> Void) {
        self.presentation = presentation
        self.onClose = onClose
        _analytics = State(initialValue: SupportPromptAnalyticsSession(
            source: presentation == .prompt ? .auto : .settings
        ))
    }

    var body: some View {
        // Scrolls only when it must: in landscape on a small iPhone the measured
        // height is taller than the screen, and the amounts and the way out stay
        // reachable.
        ScrollView {
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
        }
        .scrollBounceBehavior(.basedOnSize)
        #if os(macOS)
        .frame(width: SceneViewTokens.Layout.sheetWidthMac, height: contentHeight)
        #endif
        .presentationDetents([.height(contentHeight)])
        .presentationDragIndicator(.visible)
        .presentationBackground(SceneViewTokens.HomeColor.surfaceContainer)
        .sensoryFeedback(.success, trigger: store.purchaseState == .thanked)
        .onChange(of: store.purchaseState) { _, state in announce(state) }
        .task { await store.loadProducts() }
        .onAppear { analytics.shown() }
        .onDisappear { analytics.close() }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("support-sheet")
    }

    /// VoiceOver hears the outcome of the purchase and lands on it.
    private func announce(_ state: SupportStore.PurchaseState) {
        let target: Focus
        let text: String
        switch state {
        case .thanked:
            target = .thanks
            text = "Thank you! " + Self.thanksBody
        case .pending, .failed:
            target = .status
            text = statusText
        case .idle, .purchasing:
            return
        }
        var announcement = AttributedString(text)
        announcement.accessibilitySpeechAnnouncementPriority = .high
        AccessibilityNotification.Announcement(announcement).post()
        focus = target
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
                    secondaryButton("Not now", id: "support-not-now") { close(.notNow) }
                    secondaryButton("Don't ask again", id: "support-never-ask") {
                        store.neverAskAgain()
                        close(.never)
                    }
                }
            } else {
                secondaryButton("Close", id: "support-close") { close() }
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
                ForEach(store.products, id: \.id) { product in
                    amountButton(product)
                }
            }
        }
    }

    private var statusText: String {
        switch store.purchaseState {
        case .pending:
            "Waiting for approval. Thank you for asking."
        case .failed:
            "The purchase did not go through. You can try again."
        default:
            "A one-time purchase. It unlocks nothing: every feature stays free."
        }
    }

    private var status: some View {
        note(statusText)
            .accessibilityFocused($focus, equals: .status)
    }

    private static let amountHeight = SceneViewTokens.Layout.supportAmountHeight
    private static let thanksBody =
        "Your support keeps SceneView open source and maintained, for everyone who builds with it."

    /// The tier of a product, from the last part of its id (`….support.medium`): the
    /// label and the emphasis never depend on how many products the store returned.
    private static func tier(of product: Product) -> (key: String, label: String) {
        let key = product.id.split(separator: ".").last.map(String.init) ?? product.id
        return (key, key.prefix(1).uppercased() + key.dropFirst())
    }

    private func amountButton(_ product: Product) -> some View {
        let (key, label) = Self.tier(of: product)
        let emphasised = key == "medium"
        let busy = store.purchaseState == .purchasing(product.id)
        let fill = emphasised ? SceneViewTokens.HomeColor.primary : SceneViewTokens.HomeColor.secondaryContainer
        let ink = emphasised ? SceneViewTokens.HomeColor.onPrimary : SceneViewTokens.HomeColor.onSecondaryContainer
        let shape = RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous)
        return Button {
            Task { await store.purchase(product, analytics: analytics) }
        } label: {
            VStack(spacing: SceneViewTokens.Space.xs) {
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
        .accessibilityIdentifier("support-tier-\(key)")
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
                    .accessibilityFocused($focus, equals: .thanks)
                Text(Self.thanksBody)
                    .font(SceneViewTokens.TypeScale.body)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button(action: { close() }) {
                Text("Done")
                    .font(SceneViewTokens.TypeScale.bodySemibold)
                    .foregroundStyle(SceneViewTokens.HomeColor.onPrimary)
                    .frame(maxWidth: .infinity, minHeight: SceneViewTokens.Layout.touchTarget)
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

    private func close(_ result: SupportPromptResult? = nil) {
        analytics.close(result: result)
        onClose()
    }
}

private extension SupportStore.PurchaseState {
    var isPurchasing: Bool {
        if case .purchasing = self { return true }
        return false
    }
}

import CalibrationKit
import SwiftUI
import UIKit

/// The results of a calibration (Before vs After): one report panel (the headline, each score on its
/// gauge, the white point and the colours, all with where they started), then the newsletter, the
/// grey-by-grey chart (pushed) and the image to share.
/// Shown by the After step; the step screen around it supplies the nav title and the pinned main action.
struct ResultsView: View {
    let report: Report
    var before: Report?
    let hdr: Bool
    let mode: SignalMode
    let tvName: String
    /// When the After check was measured: the date line and the shared summary.
    var date = Date()
    /// The kind of screen, for the iPhone's margin of error; nil hides it (the demo's readings are simulated).
    var margin: PanelType? = nil
    /// The Untangled newsletter card, right under the scores.
    var showsNewsletter = false

    @EnvironmentObject var app: AppModel
    @State private var shareImage: Image?

    private var headline: String {
        guard let before else { return "Your picture, measured." }
        let now = Analysis.greyAverage(report, hdr: hdr), was = Analysis.greyAverage(before, hdr: hdr)
        if now.isFinite, was.isFinite, now > was + 0.3 { return "Not quite there yet." }
        return "A more natural picture."
    }

    private var shareTitle: String { tvName + " · calibration results" }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // The report: the headline, each score on its gauge, the white point and the colours.
            ReportPanel {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(before != nil ? "BEFORE \u{2192} AFTER" : "MEASURED")
                        .font(.badge)
                        .tracking(0.8)
                        .foregroundStyle(Theme.accent)
                    Spacer(minLength: 8)
                    // The screen's name and format are in the step's header, just above.
                    Text(date.formatted(date: .abbreviated, time: .omitted))
                        .font(.small)
                        .secondaryText()
                }
                Text(headline)
                    .font(.pageTitle)
                    .tracking(-0.6)
                    .foregroundStyle(.white)
                    .fixedSize(horizontal: false, vertical: true)
                ReportHero(report: report, before: before, hdr: hdr,
                           margin: margin.flatMap { app.profiles.marginDE(panel: $0) })
            }

            if showsNewsletter { NewsletterCard() }
            ResultWarnings(warnings: report.warnings)

            HStack(alignment: .center, spacing: 12) {
                NavigationLink {
                    ResultsChartsView(report: report, before: before, hdr: hdr, mode: mode, tvName: tvName)
                } label: {
                    Label("Grey by grey", systemImage: "chart.xyaxis.line")
                }
                .buttonStyle(LinkButtonStyle(font: .callout14))
                Spacer(minLength: 8)
                shareButton
            }
            .frame(minHeight: 44)
            ScoreInfo(margin: margin)
        }
        .onAppear {
            if shareImage == nil { shareImage = renderSummary() }
        }
        .onChange(of: report) {
            shareImage = renderSummary()
        }
    }

    private var shareLabel: some View {
        HStack(spacing: 6) {
            Image(systemName: "square.and.arrow.up")
            Text("Share")
        }
    }

    @ViewBuilder private var shareButton: some View {
        if let image = shareImage {
            ShareLink(item: image, preview: SharePreview(shareTitle, image: image)) {
                shareLabel
            }
            .buttonStyle(LinkButtonStyle(font: .callout14))
        } else {
            shareLabel
                .font(.callout14)
                .foregroundStyle(Theme.textSecondary)
                .frame(minHeight: 44)
        }
    }

    /// The shareable summary card (393 x 660) as an image.
    private func renderSummary() -> Image? {
        let card = ShareSummaryCard(report: report, before: before, hdr: hdr, mode: mode, tvName: tvName, date: date,
                                    margin: margin.flatMap { app.profiles.marginDE(panel: $0) })
            .frame(width: 393, height: 660)
            .environment(\.colorScheme, .dark)
        let renderer = ImageRenderer(content: card)
        renderer.scale = 3
        guard let ui = renderer.uiImage else { return nil }
        return Image(uiImage: ui)
    }
}

// MARK: - Charts

/// "Your picture, in detail": the greyscale and colour charts ("12 Results · charts"), pushed from ResultsView.
struct ResultsChartsView: View {
    let report: Report
    var before: Report?
    let hdr: Bool
    let mode: SignalMode
    let tvName: String

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .center, spacing: 12) {
                    Text(tvName)
                        .font(.bodyStrong)
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    ModeBadge(mode: mode)
                }
                ResultCharts(report: report, before: before, hdr: hdr)
                ResultWarnings(warnings: report.warnings)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle("Grey by grey")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Shareable summary

/// The image people share: the screen, the format and the date, then the same report as the results
/// (scores on their gauges, the white point, the colours), drawn once with no animation.
struct ShareSummaryCard: View {
    let report: Report
    var before: Report?
    let hdr: Bool
    let mode: SignalMode
    let tvName: String
    var date = Date()
    /// The iPhone's margin of error, in dE.
    var margin: Double? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .center, spacing: 10) {
                Image("SlidebeanLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(height: 22)
                Text("TV Calibrator")
                    .font(.bodyStrong)
                    .foregroundStyle(.white)
                Spacer(minLength: 8)
                ModeBadge(mode: mode)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(tvName)
                    .font(.brand(30, .regular, relativeTo: .largeTitle))
                    .tracking(-0.6)
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                Text((before != nil ? "Before \u{2192} after · " : "Measured · ") + date.formatted(date: .long, time: .omitted))
                    .font(.captionBrand)
                    .secondaryText()
            }
            ReportHero(report: report, before: before, hdr: hdr, margin: margin, animated: false)
            Spacer(minLength: 0)
            HStack(alignment: .firstTextBaseline) {
                Text("by Slidebean")
                    .font(.callout14)
                    .foregroundStyle(Theme.accent)
                Spacer(minLength: 8)
                Text("Measured with iPhone")
                    .font(.tiny)
                    .secondaryText()
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Theme.tealGradient, in: RoundedRectangle(cornerRadius: 24))
        .background(Theme.background, in: RoundedRectangle(cornerRadius: 24))
        .overlay(RoundedRectangle(cornerRadius: 24).stroke(Theme.hairline, lineWidth: 1))
    }
}

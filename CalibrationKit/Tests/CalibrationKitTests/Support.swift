import Foundation
import XCTest
@testable import CalibrationKit

/// Fixtures are written by tools/make_fixtures.py from the Mac app's Python engine.
enum Fixture {
    static func load(_ name: String) throws -> Any {
        guard let url = Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures") else {
            throw NSError(domain: "fixtures", code: 1, userInfo: [NSLocalizedDescriptionKey: "Missing fixture \(name).json"])
        }
        return try JSONSerialization.jsonObject(with: Data(contentsOf: url), options: [.fragmentsAllowed])
    }

    static func decode<T: Decodable>(_ type: T.Type, _ obj: Any) throws -> T {
        let data = try JSONSerialization.data(withJSONObject: obj, options: [.fragmentsAllowed])
        return try JSONDecoder().decode(T.self, from: data)
    }
}

func num(_ any: Any?) -> Double? {
    if any is NSNull { return nil }
    return (any as? NSNumber)?.doubleValue
}

func vec3(_ any: Any?) -> Vec3 {
    let a = any as! [Any]
    return Vec3(num(a[0])!, num(a[1])!, num(a[2])!)
}

func point(_ any: Any?) -> Point2 {
    let a = any as! [Any]
    return Point2(num(a[0])!, num(a[1])!)
}

func dict(_ any: Any?) -> [String: Any] { any as? [String: Any] ?? [:] }
func list(_ any: Any?) -> [Any] { any as? [Any] ?? [] }

/// Equal within a relative tolerance (and an absolute one near zero). nil == nil; NaN matches nil.
func assertClose(_ a: Double?, _ b: Double?, _ tol: Double = 1e-6, _ message: @autoclosure () -> String = "",
                 file: StaticString = #filePath, line: UInt = #line) {
    let a = (a?.isNaN ?? false) ? nil : a
    let b = (b?.isNaN ?? false) ? nil : b
    switch (a, b) {
    case (nil, nil):
        return
    case let (x?, y?):
        XCTAssertLessThanOrEqual(abs(x - y), tol * max(1, abs(x), abs(y)), "\(message()): \(x) vs \(y)", file: file, line: line)
    default:
        XCTFail("\(message()): \(String(describing: a)) vs \(String(describing: b))", file: file, line: line)
    }
}

func assertClose(_ a: Vec3, _ b: Vec3, _ tol: Double = 1e-6, _ message: @autoclosure () -> String = "",
                 file: StaticString = #filePath, line: UInt = #line) {
    for i in 0..<3 { assertClose(a[i], b[i], tol, "\(message())[\(i)]", file: file, line: line) }
}

func assertClose(_ a: Point2, _ b: Point2, _ tol: Double = 1e-6, _ message: @autoclosure () -> String = "",
                 file: StaticString = #filePath, line: UInt = #line) {
    assertClose(a.x, b.x, tol, "\(message()).x", file: file, line: line)
    assertClose(a.y, b.y, tol, "\(message()).y", file: file, line: line)
}

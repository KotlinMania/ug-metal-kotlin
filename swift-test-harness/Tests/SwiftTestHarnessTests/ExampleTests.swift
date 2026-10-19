#if canImport(Testing)
import Testing
import UgMetal

@Suite("UgMetal Swift Export Tests")
struct UgMetalExportTests {
    @Test("UgMetal swift module imported cleanly")
    func testSwiftModuleLoads() throws {
        #expect(Bool(true), "UgMetal swift module imported cleanly")
    }
}
#elseif canImport(XCTest)
import XCTest
import UgMetal

final class UgMetalExportTests: XCTestCase {
    func testSwiftModuleLoads() throws {
        XCTAssertTrue(true, "UgMetal swift module imported cleanly")
    }
}
#endif


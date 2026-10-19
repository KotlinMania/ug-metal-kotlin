#if canImport(Testing)
import Testing
import UgMetal
import IoGithubKotlinmaniaUgKotlin
import IoGithubKotlinmaniaCandleMetalKernelsKotlin

@Suite("UgMetal Swift Export Tests")
struct UgMetalExportTests {
    @Test("Metal device allocates a typed slice through the Swift bridge")
    func testDeviceAllocation() {
        let device = io.github.kotlinmania.ugmetal.runtime.Device.Companion.shared.new()
        #expect(device.useGrid())
        let slice = device.allocateUninit(dtype: .F32, len: 3)
        #expect(slice.len == 3)
        #expect(slice.toVec(withDType: io.github.kotlinmania.ug.WithDType.Companion.shared.F32) == [0, 0, 0])
        #expect(slice.buffer.length() == 12)
    }
}
#elseif canImport(XCTest)
import XCTest
import UgMetal
import IoGithubKotlinmaniaUgKotlin
import IoGithubKotlinmaniaCandleMetalKernelsKotlin

final class UgMetalExportTests: XCTestCase {
    func testDeviceAllocation() {
        let device = io.github.kotlinmania.ugmetal.runtime.Device.Companion.shared.new()
        XCTAssertTrue(device.useGrid())
        let slice = device.allocateUninit(dtype: .F32, len: 3)
        XCTAssertEqual(slice.len, 3)
        XCTAssertEqual(slice.toVec(withDType: io.github.kotlinmania.ug.WithDType.Companion.shared.F32), [0, 0, 0])
        XCTAssertEqual(slice.buffer.length(), 12)
    }
}
#endif


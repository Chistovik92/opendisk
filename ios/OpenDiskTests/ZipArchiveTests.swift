import XCTest

/// Архивы: туда и обратно, архив, собранный другой программой, и главное —
/// чужой архив не должен писать за пределы папки назначения.
/// Те же случаи, что в android-app/.../ArchivesTest.kt.
final class ZipArchiveTests: XCTestCase {

    private var work: URL!

    override func setUpWithError() throws {
        work = FileManager.default.temporaryDirectory.appendingPathComponent("zip-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: work)
    }

    private func write(_ text: String, to relative: String) throws {
        let url = work.appendingPathComponent(relative)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try text.write(to: url, atomically: true, encoding: .utf8)
    }

    func testFilesAndFoldersSurviveARoundTrip() throws {
        try write("первый", to: "проект/a.txt")
        try write("второй", to: "проект/sub/deep/b.txt")
        try FileManager.default.createDirectory(at: work.appendingPathComponent("проект/empty"), withIntermediateDirectories: true)
        // Большой и плохо сжимаемый файл: проверяет, что поток работает кусками.
        var big = Data()
        var state: UInt32 = 12_345
        for _ in 0..<300_000 {
            state = state &* 1_664_525 &+ 1_013_904_223
            big.append(UInt8(truncatingIfNeeded: state >> 16))
        }
        try big.write(to: work.appendingPathComponent("проект/big.bin"))

        let zip = work.appendingPathComponent("проект.zip")
        try ZipArchive.create(from: [work.appendingPathComponent("проект")], to: zip)
        let out = work.appendingPathComponent("распаковано")
        let count = try ZipArchive.extract(zip, into: out)

        XCTAssertEqual(count, 3)
        XCTAssertEqual(try String(contentsOf: out.appendingPathComponent("проект/a.txt"), encoding: .utf8), "первый")
        XCTAssertEqual(try String(contentsOf: out.appendingPathComponent("проект/sub/deep/b.txt"), encoding: .utf8), "второй")
        XCTAssertEqual(try Data(contentsOf: out.appendingPathComponent("проект/big.bin")), big)
        var isDirectory: ObjCBool = false
        XCTAssertTrue(FileManager.default.fileExists(atPath: out.appendingPathComponent("проект/empty").path, isDirectory: &isDirectory))
        XCTAssertTrue(isDirectory.boolValue, "пустая папка не должна пропасть")
    }

    func testAnEmptyFileSurvivesARoundTrip() throws {
        try write("", to: "пустой.txt")
        let zip = work.appendingPathComponent("a.zip")
        try ZipArchive.create(from: [work.appendingPathComponent("пустой.txt")], to: zip)
        XCTAssertEqual(try ZipArchive.extract(zip, into: work.appendingPathComponent("out")), 1)
        XCTAssertEqual(try String(contentsOf: work.appendingPathComponent("out/пустой.txt"), encoding: .utf8), "")
    }

    /// Архив, собранный не нами (.NET), с кириллицей в имени и методом deflate:
    /// проверяет, что мы читаем настоящие zip, а не только свои.
    func testAnArchiveBuiltByAnotherProgramIsRead() throws {
        let data = Data(base64Encoded: ZipArchiveTests.dotnetZip)!
        let zip = work.appendingPathComponent("dotnet.zip")
        try data.write(to: zip)
        let out = work.appendingPathComponent("out")

        XCTAssertEqual(try ZipArchive.extract(zip, into: out), 2)
        XCTAssertEqual(
            try String(contentsOf: out.appendingPathComponent("привет.txt"), encoding: .utf8),
            String(repeating: "привет, мир! ", count: 40)
        )
        XCTAssertEqual(try String(contentsOf: out.appendingPathComponent("sub/b.txt"), encoding: .utf8), "second file\n")
    }

    func testAnEntryLeadingOutsideTheFolderIsRefusedAndNothingEscapes() throws {
        let zip = work.appendingPathComponent("evil.zip")
        try makeStoredZip(entries: [("ok.txt", "1"), ("../escaped.txt", "2")], at: zip)
        let out = work.appendingPathComponent("out")

        XCTAssertThrowsError(try ZipArchive.extract(zip, into: out)) { error in
            XCTAssertEqual(error as? ZipError, .unsafe("../escaped.txt"))
        }
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: work.appendingPathComponent("escaped.txt").path),
            "файл вышел за пределы папки назначения"
        )
    }

    func testAbsolutePathsInAnArchiveAreRefused() throws {
        let zip = work.appendingPathComponent("abs.zip")
        try makeStoredZip(entries: [("/tmp/opendisk-victim.txt", "3")], at: zip)
        XCTAssertThrowsError(try ZipArchive.extract(zip, into: work.appendingPathComponent("out")))
        XCTAssertFalse(FileManager.default.fileExists(atPath: "/tmp/opendisk-victim.txt"))
    }

    func testABrokenArchiveLeavesNoHalfWrittenZipBehind() throws {
        let zip = work.appendingPathComponent("a.zip")
        XCTAssertThrowsError(try ZipArchive.create(from: [work.appendingPathComponent("нет такого")], to: zip))
        XCTAssertFalse(FileManager.default.fileExists(atPath: zip.path))
    }

    func testAGarbageFileIsNotAnArchive() throws {
        let zip = work.appendingPathComponent("junk.zip")
        try Data("это не zip".utf8).write(to: zip)
        XCTAssertThrowsError(try ZipArchive.extract(zip, into: work.appendingPathComponent("out")))
    }

    func testFolderNameComesFromTheArchiveName() {
        XCTAssertEqual(ZipArchive.folderName(for: "отчёт.zip"), "отчёт")
        XCTAssertEqual(ZipArchive.folderName(for: "a.b.zip"), "a.b")
        XCTAssertEqual(ZipArchive.folderName(for: "noext"), "noext")
        XCTAssertTrue(ZipArchive.isZip("ФОТО.ZIP"))
        XCTAssertFalse(ZipArchive.isZip("a.rar"))
    }

    /// Минимальный zip без сжатия с произвольными именами — в том числе
    /// такими, какие настоящие программы не пишут.
    private func makeStoredZip(entries: [(String, String)], at url: URL) throws {
        func u16(_ v: Int) -> [UInt8] { [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF)] }
        func u32(_ v: UInt32) -> [UInt8] {
            [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 24) & 0xFF)]
        }
        var body = [UInt8]()
        var central = [UInt8]()
        for (name, text) in entries {
            let nameBytes = Array(name.utf8)
            let content = Array(text.utf8)
            var crc = CRC32()
            crc.update(Data(content))
            let offset = UInt32(body.count)
            body += u32(0x0403_4B50) + u16(20) + u16(0x0800) + u16(0) + u16(0) + u16(0x21)
            body += u32(crc.value) + u32(UInt32(content.count)) + u32(UInt32(content.count))
            body += u16(nameBytes.count) + u16(0) + nameBytes + content
            central += u32(0x0201_4B50) + u16(20) + u16(20) + u16(0x0800) + u16(0) + u16(0) + u16(0x21)
            central += u32(crc.value) + u32(UInt32(content.count)) + u32(UInt32(content.count))
            central += u16(nameBytes.count) + u16(0) + u16(0) + u16(0) + u16(0) + u32(0) + u32(offset) + nameBytes
        }
        var end = u32(0x0605_4B50) + u16(0) + u16(0) + u16(entries.count) + u16(entries.count)
        end += u32(UInt32(central.count)) + u32(UInt32(body.count)) + u16(0)
        try Data(body + central + end).write(to: url)
    }

    /// Архив из двух записей — «привет.txt» (deflate) и «sub/b.txt», — собранный .NET.
    private static let dotnetZip = "UEsDBBQAAAgIACCZQl0Z9JdbIwAAAHADAAAQAAAA0L/RgNC40LLQtdGCLnR4dLuw/2LDhR0XNl3YerFJR+HCngs7LjYoKlwYFR0VHRWlgSgAUEsDBBQAAAgAACCZQl0AAAAAAAAAAAAAAAAEAAAAc3ViL1BLAwQUAAAICAAgmUJdgv9y5A4AAAAMAAAACQAAAHN1Yi9iLnR4dCtOTc7PS1FIy8xJ5QIAUEsBAhQAFAAACAgAIJlCXRn0l1sjAAAAcAMAABAAAAAAAAAAAAAAAAAAAAAAANC/0YDQuNCy0LXRgi50eHRQSwECFAAUAAAIAAAgmUJdAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAABRAAAAc3ViL1BLAQIUABQAAAgIACCZQl2C/3LkDgAAAAwAAAAJAAAAAAAAAAAAAAAAAHMAAABzdWIvYi50eHRQSwUGAAAAAAMAAwCnAAAAqAAAAAAA"
}

import Compression
import Foundation

// Zip-архивы: распаковать и сжать — зеркало android-app/.../Archives.kt.
//
// В iOS нет готового zip, поэтому формат разобран вручную поверх Compression
// (его COMPRESSION_ZLIB — это «голый» deflate без заголовка, ровно то, что
// кладёт в zip метод 8). Zip64 не поддерживается: архивы больше 4 ГБ на
// телефоне — редкость, а отказ с понятным сообщением лучше, чем неверная
// распаковка.

enum ZipError: LocalizedError, Equatable {
    case unsupported(String)
    case unsafe(String)
    case corrupt(String)
    case noSpace(Int64)

    var errorDescription: String? {
        switch self {
        case .unsupported(let what): return "unsupported zip: \(what)"
        case .unsafe(let name): return "unsafe path in archive: \(name)"
        case .corrupt(let what): return "corrupt zip: \(what)"
        case .noSpace(let bytes): return "not enough space: need \(bytes) bytes"
        }
    }
}

enum ZipArchive {

    /// Запись из центрального каталога архива.
    struct Item {
        let name: String
        let method: UInt16
        let crc: UInt32
        let compressedSize: UInt64
        let size: UInt64
        let localOffset: UInt64
        let modified: Date?
        var isDirectory: Bool { name.hasSuffix("/") }
    }

    /// Имя папки для распаковки: «отчёт.zip» → «отчёт».
    static func folderName(for zipName: String) -> String {
        guard let dot = zipName.lastIndex(of: "."), dot != zipName.startIndex else { return zipName }
        return String(zipName[..<dot])
    }

    static func isZip(_ name: String) -> Bool { name.lowercased().hasSuffix(".zip") }

    // MARK: - Чтение

    /// Распаковывает `zip` в новую папку `into` и возвращает число файлов.
    ///
    /// Два условия безопасности — те же, что на Android. Имя записи в архиве —
    /// чужая строка: «../../x» вывело бы запись за пределы папки назначения
    /// (уязвимость zip-slip), поэтому каждый путь сверяется с папкой. И места
    /// должно хватить на всё распакованное: архив в килобайты может раскрыться
    /// в гигабайты.
    @discardableResult
    static func extract(_ zip: URL, into: URL) throws -> Int {
        let items = try self.items(of: zip)
        let root = into.standardizedFileURL
        let total = items.filter { !$0.isDirectory }.reduce(Int64(0)) { $0 + Int64($1.size) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        if let free = try? root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage, total > free {
            throw ZipError.noSpace(total)
        }

        let input = try FileHandle(forReadingFrom: zip)
        defer { try? input.close() }

        var files = 0
        for item in items {
            let target = root.appendingPathComponent(item.name).standardizedFileURL
            guard !item.name.hasPrefix("/"),
                  target.path == root.path || target.path.hasPrefix(root.path + "/")
            else { throw ZipError.unsafe(item.name) }

            if item.isDirectory {
                try FileManager.default.createDirectory(at: target, withIntermediateDirectories: true)
                continue
            }
            try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            FileManager.default.createFile(atPath: target.path, contents: nil)
            let output = try FileHandle(forWritingTo: target)
            do {
                let start = try dataStart(of: item, in: input)
                try input.seek(toOffset: start)
                let result = try item.method == 0
                    ? copyStored(from: input, count: item.compressedSize, to: output)
                    : try inflate(from: input, compressedSize: item.compressedSize, to: output)
                try output.close()
                if result.crc != item.crc || result.bytes != item.size {
                    throw ZipError.corrupt("checksum of \(item.name)")
                }
            } catch {
                try? output.close()
                try? FileManager.default.removeItem(at: target)
                throw error
            }
            if let modified = item.modified {
                try? FileManager.default.setAttributes([.modificationDate: modified], ofItemAtPath: target.path)
            }
            files += 1
        }
        return files
    }

    /// Записи архива из центрального каталога.
    static func items(of zip: URL) throws -> [Item] {
        let handle = try FileHandle(forReadingFrom: zip)
        defer { try? handle.close() }

        let fileSize = try handle.seekToEnd()
        // Конец архива: подпись, 18 байт полей и комментарий до 65535 байт.
        let tailSize = min(fileSize, 22 + 65_535)
        try handle.seek(toOffset: fileSize - tailSize)
        let tail = try handle.read(upToCount: Int(tailSize)) ?? Data()
        guard let eocd = findEndOfCentralDirectory(in: tail) else { throw ZipError.corrupt("no end of central directory") }

        let count = UInt64(tail.u16(eocd + 10))
        let directorySize = UInt64(tail.u32(eocd + 12))
        let directoryOffset = UInt64(tail.u32(eocd + 16))
        if count == 0xFFFF || directorySize == 0xFFFF_FFFF || directoryOffset == 0xFFFF_FFFF {
            throw ZipError.unsupported("zip64")
        }
        guard directoryOffset + directorySize <= fileSize else { throw ZipError.corrupt("central directory out of range") }

        try handle.seek(toOffset: directoryOffset)
        let directory = try handle.read(upToCount: Int(directorySize)) ?? Data()
        var items: [Item] = []
        var position = 0
        for _ in 0..<count {
            guard position + 46 <= directory.count, directory.u32(position) == 0x0201_4B50 else {
                throw ZipError.corrupt("central directory entry")
            }
            let flags = directory.u16(position + 8)
            let method = directory.u16(position + 10)
            let time = directory.u16(position + 12)
            let date = directory.u16(position + 14)
            let nameLength = Int(directory.u16(position + 28))
            let extraLength = Int(directory.u16(position + 30))
            let commentLength = Int(directory.u16(position + 32))
            guard position + 46 + nameLength <= directory.count else { throw ZipError.corrupt("entry name") }
            guard method == 0 || method == 8 else { throw ZipError.unsupported("compression method \(method)") }
            if flags & 1 != 0 { throw ZipError.unsupported("encrypted entries") }

            let nameBytes = directory.subdata(in: (position + 46)..<(position + 46 + nameLength))
            // Бит 11 флагов — имя в UTF-8; без него формально CP437, но на деле
            // почти всё пишут в UTF-8, и Latin-1 только испортил бы кириллицу.
            let name = String(data: nameBytes, encoding: .utf8) ?? String(data: nameBytes, encoding: .isoLatin1) ?? ""

            items.append(Item(
                name: name,
                method: method,
                crc: directory.u32(position + 16),
                compressedSize: UInt64(directory.u32(position + 20)),
                size: UInt64(directory.u32(position + 24)),
                localOffset: UInt64(directory.u32(position + 42)),
                modified: dosDate(date: date, time: time)
            ))
            position += 46 + nameLength + extraLength + commentLength
        }
        return items
    }

    private static func findEndOfCentralDirectory(in tail: Data) -> Int? {
        guard tail.count >= 22 else { return nil }
        var index = tail.count - 22
        while index >= 0 {
            if tail.u32(index) == 0x0605_4B50 { return index }
            index -= 1
        }
        return nil
    }

    /// Где в файле начинаются данные записи: за локальным заголовком, у
    /// которого своя длина имени и дополнительных полей.
    private static func dataStart(of item: Item, in handle: FileHandle) throws -> UInt64 {
        try handle.seek(toOffset: item.localOffset)
        guard let header = try handle.read(upToCount: 30), header.count == 30, header.u32(0) == 0x0403_4B50 else {
            throw ZipError.corrupt("local header of \(item.name)")
        }
        return item.localOffset + 30 + UInt64(header.u16(26)) + UInt64(header.u16(28))
    }

    private static func copyStored(from input: FileHandle, count: UInt64, to output: FileHandle) throws -> (crc: UInt32, bytes: UInt64) {
        var crc = CRC32()
        var remaining = count
        while remaining > 0 {
            guard let chunk = try input.read(upToCount: Int(min(remaining, 65_536))), !chunk.isEmpty else {
                throw ZipError.corrupt("unexpected end of data")
            }
            crc.update(chunk)
            try output.write(contentsOf: chunk)
            remaining -= UInt64(chunk.count)
        }
        return (crc.value, count)
    }

    private static func inflate(from input: FileHandle, compressedSize: UInt64, to output: FileHandle) throws -> (crc: UInt32, bytes: UInt64) {
        var crc = CRC32()
        var written: UInt64 = 0
        try runStream(operation: COMPRESSION_STREAM_DECODE, input: input, count: compressedSize) { chunk in
            crc.update(chunk)
            written += UInt64(chunk.count)
            try output.write(contentsOf: chunk)
        }
        return (crc.value, written)
    }

    // MARK: - Запись

    /// Складывает `sources` (файлы и папки целиком) в `destination`.
    ///
    /// Если что-то пошло не так посередине, недописанный архив удаляется:
    /// обрезанный zip выглядит настоящим и ломается только при распаковке.
    static func create(from sources: [URL], to destination: URL) throws {
        FileManager.default.createFile(atPath: destination.path, contents: nil)
        let output = try FileHandle(forWritingTo: destination)
        var central = Data()
        var offset: UInt64 = 0
        var count = 0
        do {
            for source in sources {
                try add(source, as: source.lastPathComponent, to: output, central: &central, offset: &offset, count: &count)
            }
            try output.write(contentsOf: central)
            var end = Data()
            end.appendU32(0x0605_4B50)
            end.appendU16(0); end.appendU16(0)
            end.appendU16(UInt16(count)); end.appendU16(UInt16(count))
            end.appendU32(UInt32(central.count))
            end.appendU32(UInt32(offset))
            end.appendU16(0)
            try output.write(contentsOf: end)
            try output.close()
        } catch {
            try? output.close()
            try? FileManager.default.removeItem(at: destination)
            throw error
        }
    }

    private static func add(_ url: URL, as name: String, to output: FileHandle, central: inout Data, offset: inout UInt64, count: inout Int) throws {
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory) else {
            throw ZipError.corrupt("no such file: \(url.lastPathComponent)")
        }
        let modified = (try? FileManager.default.attributesOfItem(atPath: url.path)[.modificationDate] as? Date) ?? Date()
        let (dosTime, dosDate) = dosFields(modified)

        if isDirectory.boolValue {
            let entryName = name + "/"
            try writeEntry(name: entryName, method: 0, flags: 0x0800, time: dosTime, date: dosDate, crc: 0, compressed: 0, size: 0,
                           body: nil, to: output, central: &central, offset: &offset, count: &count)
            let children = try FileManager.default.contentsOfDirectory(atPath: url.path).sorted()
            for child in children {
                try add(url.appendingPathComponent(child), as: name + "/" + child, to: output, central: &central, offset: &offset, count: &count)
            }
            return
        }

        // Заголовок идёт до данных, а размеры известны только после них, —
        // поэтому флаг 3 («размеры в дескрипторе после данных»).
        let headerOffset = offset
        var header = Data()
        let nameBytes = Data(name.utf8)
        header.appendU32(0x0403_4B50)
        header.appendU16(20); header.appendU16(0x0808)
        header.appendU16(8)
        header.appendU16(dosTime); header.appendU16(dosDate)
        header.appendU32(0); header.appendU32(0); header.appendU32(0)
        header.appendU16(UInt16(nameBytes.count)); header.appendU16(0)
        header.append(nameBytes)
        try output.write(contentsOf: header)
        offset += UInt64(header.count)

        let input = try FileHandle(forReadingFrom: url)
        defer { try? input.close() }
        let size = try input.seekToEnd()
        try input.seek(toOffset: 0)
        if size > 0xFFFF_FFFF { throw ZipError.unsupported("file larger than 4 GB: \(name)") }

        var crc = CRC32()
        var compressed: UInt64 = 0
        try runStream(operation: COMPRESSION_STREAM_ENCODE, input: input, count: size, observeInput: { crc.update($0) }) { chunk in
            compressed += UInt64(chunk.count)
            try output.write(contentsOf: chunk)
        }
        offset += compressed

        var descriptor = Data()
        descriptor.appendU32(0x0807_4B50)
        descriptor.appendU32(crc.value)
        descriptor.appendU32(UInt32(compressed))
        descriptor.appendU32(UInt32(size))
        try output.write(contentsOf: descriptor)
        offset += UInt64(descriptor.count)

        appendCentral(name: nameBytes, method: 8, flags: 0x0808, time: dosTime, date: dosDate,
                      crc: crc.value, compressed: UInt32(compressed), size: UInt32(size), localOffset: UInt32(headerOffset), to: &central)
        count += 1
    }

    private static func writeEntry(name: String, method: UInt16, flags: UInt16, time: UInt16, date: UInt16, crc: UInt32,
                                   compressed: UInt32, size: UInt32, body: Data?, to output: FileHandle,
                                   central: inout Data, offset: inout UInt64, count: inout Int) throws {
        let nameBytes = Data(name.utf8)
        var header = Data()
        header.appendU32(0x0403_4B50)
        header.appendU16(20); header.appendU16(flags)
        header.appendU16(method)
        header.appendU16(time); header.appendU16(date)
        header.appendU32(crc); header.appendU32(compressed); header.appendU32(size)
        header.appendU16(UInt16(nameBytes.count)); header.appendU16(0)
        header.append(nameBytes)
        let headerOffset = offset
        try output.write(contentsOf: header)
        offset += UInt64(header.count)
        appendCentral(name: nameBytes, method: method, flags: flags, time: time, date: date,
                      crc: crc, compressed: compressed, size: size, localOffset: UInt32(headerOffset), to: &central)
        count += 1
    }

    private static func appendCentral(name: Data, method: UInt16, flags: UInt16, time: UInt16, date: UInt16, crc: UInt32,
                                      compressed: UInt32, size: UInt32, localOffset: UInt32, to central: inout Data) {
        central.appendU32(0x0201_4B50)
        central.appendU16(20); central.appendU16(20)
        central.appendU16(flags); central.appendU16(method)
        central.appendU16(time); central.appendU16(date)
        central.appendU32(crc); central.appendU32(compressed); central.appendU32(size)
        central.appendU16(UInt16(name.count))
        central.appendU16(0); central.appendU16(0); central.appendU16(0); central.appendU16(0)
        central.appendU32(0)
        central.appendU32(localOffset)
        central.append(name)
    }

    // MARK: - Поток Compression

    /// Гонит `count` байт из `input` через deflate (сжатие или распаковка) и
    /// отдаёт результат кусками. Потоком, а не целиком: фотография на сотню
    /// мегабайт не должна лечь в память.
    ///
    /// Устроено по образцу из документации Compression: на каждый кусок входа
    /// зовём `compression_stream_process`, пока он не съеден; на последнем —
    /// с флагом FINALIZE, пока поток не скажет END.
    private static func runStream(
        operation: compression_stream_operation,
        input: FileHandle,
        count: UInt64,
        observeInput: ((Data) -> Void)? = nil,
        sink: (Data) throws -> Void
    ) throws {
        let bufferSize = 65_536
        let destination = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        let streamPointer = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer {
            destination.deallocate()
            streamPointer.deallocate()
        }
        var stream = streamPointer.pointee
        guard compression_stream_init(&stream, operation, COMPRESSION_ZLIB) != COMPRESSION_STATUS_ERROR else {
            throw ZipError.corrupt("cannot start the compressor")
        }
        defer { compression_stream_destroy(&stream) }

        stream.dst_ptr = destination
        stream.dst_size = bufferSize

        var remaining = count
        var status = COMPRESSION_STATUS_OK
        repeat {
            let want = Int(min(remaining, UInt64(bufferSize)))
            let chunk = want > 0 ? (try input.read(upToCount: want) ?? Data()) : Data()
            if want > 0 && chunk.isEmpty { throw ZipError.corrupt("unexpected end of data") }
            remaining -= UInt64(chunk.count)
            observeInput?(chunk)
            let flags: Int32 = remaining == 0 ? Int32(COMPRESSION_STREAM_FINALIZE.rawValue) : 0

            try chunk.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
                stream.src_ptr = raw.bindMemory(to: UInt8.self).baseAddress ?? UnsafePointer(destination)
                stream.src_size = chunk.count
                // Без продвижения дважды подряд — поток битый: иначе на
                // обрезанном архиве цикл не кончился бы никогда.
                var idle = 0
                repeat {
                    let before = (stream.src_size, stream.dst_size)
                    status = compression_stream_process(&stream, flags)
                    guard status != COMPRESSION_STATUS_ERROR else { throw ZipError.corrupt("deflate stream") }
                    let produced = bufferSize - stream.dst_size
                    if produced > 0 { try sink(Data(bytes: destination, count: produced)) }
                    stream.dst_ptr = destination
                    stream.dst_size = bufferSize
                    idle = (before.0 == stream.src_size && produced == 0) ? idle + 1 : 0
                    if idle >= 2 { throw ZipError.corrupt("deflate stream does not end") }
                } while status == COMPRESSION_STATUS_OK && (stream.src_size > 0 || flags != 0)
            }
        } while remaining > 0
        if status != COMPRESSION_STATUS_END { throw ZipError.corrupt("deflate stream is incomplete") }
    }

    // MARK: - Время в формате DOS

    private static func dosDate(date: UInt16, time: UInt16) -> Date? {
        var components = DateComponents()
        components.year = 1980 + Int(date >> 9)
        components.month = Int((date >> 5) & 0x0F)
        components.day = Int(date & 0x1F)
        components.hour = Int(time >> 11)
        components.minute = Int((time >> 5) & 0x3F)
        components.second = Int(time & 0x1F) * 2
        return Calendar(identifier: .gregorian).date(from: components)
    }

    private static func dosFields(_ date: Date) -> (time: UInt16, date: UInt16) {
        let c = Calendar(identifier: .gregorian).dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        let year = max(1980, c.year ?? 1980)
        let dosDate = UInt16((year - 1980) << 9 | (c.month ?? 1) << 5 | (c.day ?? 1))
        let dosTime = UInt16((c.hour ?? 0) << 11 | (c.minute ?? 0) << 5 | ((c.second ?? 0) / 2))
        return (dosTime, dosDate)
    }
}

// MARK: - Байты и контрольная сумма

/// CRC-32 по таблице: готового в Foundation нет, а zlib напрямую не импортируется.
struct CRC32 {
    private(set) var value: UInt32 = 0
    private var state: UInt32 = 0xFFFF_FFFF

    private static let table: [UInt32] = (0..<256).map { index -> UInt32 in
        var c = UInt32(index)
        for _ in 0..<8 { c = c & 1 != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    mutating func update(_ data: Data) {
        for byte in data { state = CRC32.table[Int((state ^ UInt32(byte)) & 0xFF)] ^ (state >> 8) }
        value = state ^ 0xFFFF_FFFF
    }
}

extension Data {
    fileprivate func u16(_ offset: Int) -> UInt16 {
        UInt16(self[startIndex + offset]) | UInt16(self[startIndex + offset + 1]) << 8
    }

    fileprivate func u32(_ offset: Int) -> UInt32 {
        UInt32(u16(offset)) | UInt32(u16(offset + 2)) << 16
    }

    fileprivate mutating func appendU16(_ value: UInt16) {
        append(UInt8(value & 0xFF)); append(UInt8(value >> 8))
    }

    fileprivate mutating func appendU32(_ value: UInt32) {
        appendU16(UInt16(value & 0xFFFF)); appendU16(UInt16(value >> 16))
    }
}

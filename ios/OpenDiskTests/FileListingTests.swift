import XCTest

/// Что показывает папка: типы, сортировка, скрытые, поиск, путь.
///
/// Те же случаи, что в android-app/.../FileListingTest.kt и CategoriesTest.kt:
/// интерфейс на телефонах один (docs/MOBILE-UI.md), и расхождение логики между
/// ними — ошибка. Ошибка тут не роняет приложение, а молча прячет или путает
/// файлы, поэтому проверяется отдельно от экрана.
final class FileListingTests: XCTestCase {

    private func file(_ name: String, size: Int64 = 0, time: String = "") -> Entry {
        Entry(Path: name, Name: name, Size: size, IsDir: false, ModTime: time)
    }

    private func dir(_ name: String) -> Entry {
        Entry(Path: name, Name: name, Size: -1, IsDir: true, ModTime: "")
    }

    private func names(_ entries: [Entry]) -> [String] { entries.map(\.Name) }

    func testKindFollowsTheExtensionNotTheCase() {
        XCTAssertEqual(FileKind.byName("IMG_0001.JPG"), .image)
        XCTAssertEqual(FileKind.byName("film.mkv"), .video)
        XCTAssertEqual(FileKind.byName("song.flac"), .audio)
        XCTAssertEqual(FileKind.byName("отчёт.pdf"), .document)
        XCTAssertEqual(FileKind.byName("backup.tar.gz"), .archive)
        XCTAssertEqual(FileKind.byName("OpenDisk.apk"), .apk)
        XCTAssertEqual(FileKind.byName("README"), .other)
        // «.bashrc» — имя без расширения, а не файл типа «bashrc».
        XCTAssertEqual(fileExtension(".bashrc"), "")
        XCTAssertEqual(fileExtension("trailing."), "")
        XCTAssertEqual(FileKind.of(dir("DCIM")), .folder)
    }

    func testFoldersStayFirstInEveryOrderAndDirection() {
        let entries = [file("a.txt", size: 1), dir("zeta"), file("b.txt", size: 9), dir("alpha")]
        for sort in SortOrder.allCases {
            for descending in [false, true] {
                let shown = arrange(entries, ListingOptions(sort: sort, descending: descending))
                XCTAssertEqual(shown.map(\.IsDir), [true, true, false, false], "\(sort) desc=\(descending)")
            }
        }
    }

    func testSortingBySizeAndDateAndDirection() {
        let entries = [
            file("mid", size: 5, time: "2026-01-02T00:00:00Z"),
            file("big", size: 9, time: "2026-01-01T00:00:00Z"),
            file("small", size: 1, time: "2026-01-03T00:00:00Z"),
        ]
        XCTAssertEqual(names(arrange(entries, ListingOptions(sort: .size))), ["small", "mid", "big"])
        XCTAssertEqual(names(arrange(entries, ListingOptions(sort: .size, descending: true))), ["big", "mid", "small"])
        XCTAssertEqual(names(arrange(entries, ListingOptions(sort: .date))), ["big", "mid", "small"])
        XCTAssertEqual(names(arrange(entries, ListingOptions(sort: .name))), ["big", "mid", "small"])
    }

    func testNameSortIgnoresCase() {
        XCTAssertEqual(names(arrange([file("b"), file("A"), file("c")], ListingOptions())), ["A", "b", "c"])
    }

    func testHiddenFilesAreShownOnlyOnRequest() {
        let entries = [file(".nomedia"), file("photo.jpg"), dir(".thumbnails")]
        XCTAssertEqual(names(arrange(entries, ListingOptions())), ["photo.jpg"])
        XCTAssertEqual(arrange(entries, ListingOptions(showHidden: true)).count, 3)
    }

    func testSearchMatchesPartOfTheNameInAnyCase() {
        let entries = [file("Holiday.JPG"), file("notes.txt"), dir("Holidays")]
        XCTAssertEqual(names(arrange(entries, ListingOptions(), query: "holi")), ["Holidays", "Holiday.JPG"])
        XCTAssertEqual(arrange(entries, ListingOptions(), query: "нет такого").count, 0)
        XCTAssertEqual(arrange(entries, ListingOptions(), query: "  ").count, 3)
    }

    func testBreadcrumbsLeadToEveryLevel() {
        let crumbs = breadcrumbs(diskTitle: "Телефон", path: "DCIM/Camera/2026")
        XCTAssertEqual(crumbs.map(\.label), ["Телефон", "DCIM", "Camera", "2026"])
        XCTAssertEqual(crumbs.map(\.path), ["", "DCIM", "DCIM/Camera", "DCIM/Camera/2026"])
        XCTAssertEqual(breadcrumbs(diskTitle: "Яндекс", path: ""), [Crumb(label: "Яндекс", path: "")])
    }

    func testRangeSelectionCoversEverythingBetweenInEitherDirection() {
        let list = ["a", "b", "c", "d", "e"].map { file($0) }
        XCTAssertEqual(rangeBetween(list, anchor: "b", target: "d"), ["b", "c", "d"])
        XCTAssertEqual(rangeBetween(list, anchor: "d", target: "b"), ["b", "c", "d"])
        XCTAssertEqual(rangeBetween(list, anchor: "c", target: "c"), ["c"])
        // Нет опорной точки, или она ушла из списка (поиск, фильтр) — только сам файл.
        XCTAssertEqual(rangeBetween(list, anchor: nil, target: "c"), ["c"])
        XCTAssertEqual(rangeBetween(list, anchor: "нет", target: "c"), ["c"])
        XCTAssertEqual(rangeBetween(list, anchor: "a", target: "нет"), [])
    }

    func testViewModesCycleThroughAllThree() {
        XCTAssertEqual(ViewMode.list.next(), .detailed)
        XCTAssertEqual(ViewMode.detailed.next(), .grid)
        XCTAssertEqual(ViewMode.grid.next(), .list)
    }

    func testCopyNameKeepsTheExtensionAtTheEnd() {
        XCTAssertEqual(copyName("отчёт.pdf", taken: ["отчёт.pdf"]), "отчёт (2).pdf")
        XCTAssertEqual(copyName("отчёт.pdf", taken: ["отчёт.pdf", "отчёт (2).pdf"]), "отчёт (3).pdf")
        XCTAssertEqual(copyName("README", taken: ["README"]), "README (2)")
        XCTAssertEqual(copyName(".bashrc", taken: [".bashrc"]), ".bashrc (2)")
    }

    func testPathsAreJoinedAndSplitWithoutDoubledSlashes() {
        XCTAssertEqual(childPath("", "a"), "a")
        XCTAssertEqual(childPath("a/b", "c"), "a/b/c")
        XCTAssertEqual(parentPath("a/b/c"), "a/b")
        XCTAssertEqual(parentPath("a"), "")
        XCTAssertEqual(parentPath(""), "")
    }

    func testModificationTimeIsShownInTheLocalZoneAndEmptyWhenUnknown() {
        let zone = TimeZone(identifier: "Asia/Yekaterinburg")!
        let english = Locale(identifier: "en_US_POSIX")
        XCTAssertEqual(formatModTime("2026-03-05T22:30:00Z", timeZone: zone, locale: english), "6 Mar 2026, 03:30")
        // rclone отдаёт наносекунды — разбор их переживает.
        XCTAssertEqual(formatModTime("2026-03-05T22:30:00.123456789Z", timeZone: zone, locale: english), "6 Mar 2026, 03:30")
        XCTAssertEqual(formatModTime(""), "")
        XCTAssertEqual(formatModTime(nil), "")
        XCTAssertEqual(formatModTime("вчера"), "")
    }

    func testRussianCountsDeclineInThreeForms() {
        XCTAssertEqual(itemsLabel(1, russian: true), "1 элемент")
        XCTAssertEqual(itemsLabel(3, russian: true), "3 элемента")
        XCTAssertEqual(itemsLabel(5, russian: true), "5 элементов")
        XCTAssertEqual(itemsLabel(11, russian: true), "11 элементов")
        XCTAssertEqual(itemsLabel(21, russian: true), "21 элемент")
        XCTAssertEqual(itemsLabel(1, russian: false), "1 item")
        XCTAssertEqual(itemsLabel(2, russian: false), "2 items")
    }

    // MARK: - Повторный вход и токены
    //
    // Те же случаи, что в AuthErrorsTest.kt и TokenRefreshTest.kt на Android.

    func testExpiredAccessIsRecognizedByRclonesOwnWords() {
        XCTAssertTrue(AuthErrors.isExpired("oauth2: cannot fetch token: 400 Bad Request: invalid_grant"))
        XCTAssertTrue(AuthErrors.isExpired("couldn't fetch token: maybe it has expired - refresh with \"rclone config reconnect gdrive:\""))
        XCTAssertTrue(AuthErrors.isExpired("Token Expired"))
        XCTAssertTrue(AuthErrors.isExpired("empty token found - please run rclone config again"))
        XCTAssertFalse(AuthErrors.isExpired("no such host"))
        XCTAssertFalse(AuthErrors.isExpired(""))
    }

    func testANewlyExpiredCloudIsAddedToTheList() {
        XCTAssertEqual(mergeNeedsSignIn(before: [], existing: ["gdrive", "yandex"], expired: ["gdrive"]), ["gdrive"])
    }

    func testACloudThatAnsweredIsNoLongerMarked() {
        // Вошли заново (или токен ожил) — ярлык снимается.
        XCTAssertEqual(mergeNeedsSignIn(before: ["gdrive"], existing: ["gdrive"], expired: []), [])
    }

    func testACloudTheCheckCouldNotReachKeepsItsMark() {
        // Нет сети: по неудачной проверке о токене не судим, ярлык остаётся как был.
        XCTAssertEqual(mergeNeedsSignIn(before: ["gdrive"], existing: ["gdrive"], expired: [], unreachable: ["gdrive"]), ["gdrive"])
        // И здоровому ярлык не вешается.
        XCTAssertEqual(mergeNeedsSignIn(before: [], existing: ["yandex"], expired: [], unreachable: ["yandex"]), [])
    }

    func testADeletedCloudDisappearsFromTheList() {
        XCTAssertEqual(
            mergeNeedsSignIn(before: ["gdrive", "yandex"], existing: ["yandex"], expired: [], unreachable: ["yandex"]),
            ["yandex"]
        )
    }

    // MARK: - Закладки и категории

    func testBookmarksSurviveBeingSavedAndLoadedInOrder() {
        let list = [
            Bookmark(disk: .local, path: "DCIM/Camera"),
            Bookmark(disk: .cloud("gdrive"), path: ""),
            Bookmark(disk: .cloud("яндекс"), path: "Музыка"),
        ]
        XCTAssertEqual(Bookmarks.decode(Bookmarks.encode(list)), list)
    }

    func testBrokenOrEmptyBookmarkDataGivesAnEmptyListNotACrash() {
        XCTAssertEqual(Bookmarks.decode(nil), [])
        XCTAssertEqual(Bookmarks.decode(""), [])
        XCTAssertEqual(Bookmarks.decode("мусор\u{2}cloud\u{1}"), [])
    }

    func testTheSameFolderIsNotBookmarkedTwice() {
        let one = Bookmark(disk: .cloud("x"), path: "a")
        XCTAssertEqual(Bookmarks.decode(Bookmarks.encode([one, one])).count, 1)
    }

    func testCategoriesChooseByKindAndSkipHiddenAndFolders() {
        let now = 1_800_000_000.0
        let entries = [
            file("DCIM/a.jpg", size: 1), file("DCIM/.thumbnails/b.jpg", size: 1), dir("DCIM"),
            file("Music/c.mp3", size: 1), file("Docs/d.PDF", size: 1), file("Docs/e.html", size: 1),
            file("Download/f.zip", size: 1),
        ]
        func pick(_ category: FileCategory) -> [String] { category.select(from: entries, now: now).map(\.Path).sorted() }
        XCTAssertEqual(pick(.images), ["DCIM/a.jpg"])
        XCTAssertEqual(pick(.audio), ["Music/c.mp3"])
        XCTAssertEqual(pick(.video), [])
        // html — документ для значка, но не для категории: список тот же, что на Android.
        XCTAssertEqual(pick(.documents), ["Docs/d.PDF"])
        XCTAssertEqual(pick(.downloads), ["Download/f.zip"])
    }

    func testNewMeansAddedWithinAWeekAndNonEmpty() {
        let now = 1_800_000_000.0
        let fresh = ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: now - 86_400))
        let stale = ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: now - 8 * 86_400))
        let entries = [file("fresh.txt", size: 5, time: fresh), file("stale.txt", size: 5, time: stale), file("empty.txt", size: 0, time: fresh)]
        XCTAssertEqual(FileCategory.new.select(from: entries, now: now).map(\.Name), ["fresh.txt"])
    }

    func testCategoryListIsNewestFirst() {
        let now = 1_800_000_000.0
        func time(_ daysAgo: Double) -> String { ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: now - daysAgo * 86_400)) }
        let entries = [file("old.jpg", size: 1, time: time(3)), file("new.jpg", size: 1, time: time(1)), file("mid.jpg", size: 1, time: time(2))]
        XCTAssertEqual(FileCategory.images.select(from: entries, now: now).map(\.Name), ["new.jpg", "mid.jpg", "old.jpg"])
    }
}

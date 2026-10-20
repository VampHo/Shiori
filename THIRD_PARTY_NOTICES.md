# Shiori acknowledgements

Shiori is derived from [Kotatsu](https://github.com/KotatsuApp/Kotatsu).
Original copyrights and the GNU General Public License version 3 are retained.

The Royal Road selectors in `RoyalRoadRepository.kt` are adapted from
`app/src/main/java/com/lagradost/quicknovel/providers/RoyalRoadProvider.kt`
in [QuickNovel](https://github.com/LagradOst/QuickNovel), copyright its respective
contributors, under GPL-3.0. The source was provided in QuickNovel-master.zip.
The Android UI and storage adapter are new Shiori code; the full QuickNovel
application and its bundled libraries have not been merged.

Royal Road is a third-party source, not affiliated with Shiori. Availability and
chapter access depend on the source. Shiori does not bypass login or access controls.

The source catalogue adapters in `NovelCatalogRepository.kt` and bundled source
icons are adapted from QuickNovel's RoyalRoadProvider, FreeWebNovelProvider and
LibReadProvider and corresponding drawable resources, under GPL-3.0. Original
source branding belongs to the respective sites. Novel shelf, import and screen
integration code is maintained separately from the manga implementation.

The selected novel adapters and protobuf codec additionally adapt QuickNovel's
NovelFireProvider, WattpadProvider, FenrirRealProvider, WuxiaWorldProvider,
AllNovelProvider/NovelFullProvider and ReadhiveProvider, including their source icons.

The updated manga parser dependency (dc223c3d80), detached WebView viewport and
request interception support follow the provided Kotatsu-Redo-devel source archive
from Kotatsu-Redo/Kotatsu-Redo, under GPL-3.0. The existing manga UI is retained.

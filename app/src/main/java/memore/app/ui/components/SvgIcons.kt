package memore.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val IconTimeline: ImageVector by lazy { ImageVector.Builder(
        name = "Timeline",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 20f)
            lineTo(21f, 4f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 12f)
            lineTo(9f, 6f)
            lineTo(15f, 12f)
            lineTo(21f, 6f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 4f)
            lineTo(21f, 20f)
        }
    }.build() }

val IconAnchor: ImageVector by lazy { ImageVector.Builder(
        name = "Anchor",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // 顶部圆环：连接锚链的圈
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 1.8f)
            curveTo(10.9f, 1.8f, 10f, 2.7f, 10f, 3.8f)
            curveTo(10f, 4.9f, 10.9f, 5.8f, 12f, 5.8f)
            curveTo(13.1f, 5.8f, 14f, 4.9f, 14f, 3.8f)
            curveTo(14f, 2.7f, 13.1f, 1.8f, 12f, 1.8f)
            close()
        }
        // 中轴：从圆环垂下
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 5.8f)
            lineTo(12f, 21f)
        }
        // 横杆：十字档
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(7f, 9.5f)
            lineTo(17f, 9.5f)
        }
        // 底部锚爪：两侧向上勾起的弧
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(5.5f, 13f)
            lineTo(5.5f, 15f)
            curveTo(5.5f, 19f, 8f, 21f, 12f, 21f)
            curveTo(16f, 21f, 18.5f, 19f, 18.5f, 15f)
            lineTo(18.5f, 13f)
        }
    }.build() }

/** 秒针：钟的扫描针（一根针从轴心甩向右上，背面短配重），不是表盘。 */
val IconSecondHand: ImageVector by lazy { ImageVector.Builder(
        name = "SecondHand",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // 长针：自轴心指向上方偏右（约 1 点钟方向）
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 12f)
            lineTo(19f, 4f)
        }
        // 短配重：反向朝左下
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12.3f, 12.5f)
            lineTo(8.5f, 17f)
        }
        // 轴心圆点（小菱形）
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 10.6f)
            lineTo(13.4f, 12f)
            lineTo(12f, 13.4f)
            lineTo(10.6f, 12f)
            close()
        }
    }.build() }

val IconTrash: ImageVector by lazy { ImageVector.Builder(
        name = "Trash",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 6f)
            horizontalLineTo(21f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(8f, 6f)
            verticalLineTo(4f)
            curveTo(8f, 3.45f, 8.45f, 3f, 9f, 3f)
            horizontalLineTo(15f)
            curveTo(15.55f, 3f, 16f, 3.45f, 16f, 4f)
            verticalLineTo(6f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(10f, 11f)
            verticalLineTo(17f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(14f, 11f)
            verticalLineTo(17f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(5f, 6f)
            lineTo(6f, 20f)
            curveTo(6f, 20.55f, 6.45f, 21f, 7f, 21f)
            horizontalLineTo(17f)
            curveTo(17.55f, 21f, 18f, 20.55f, 18f, 20f)
            lineTo(19f, 6f)
        }
    }.build() }

val IconSettings: ImageVector by lazy { ImageVector.Builder(
        name = "Settings",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 15f)
            curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
            curveTo(15f, 10.34f, 13.66f, 9f, 12f, 9f)
            curveTo(10.34f, 9f, 9f, 10.34f, 9f, 12f)
            curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(19.4f, 15f)
            curveTo(19.43f, 15.2f, 19.43f, 15.4f, 19.4f, 15.6f)
            lineTo(21f, 17.5f)
            lineTo(19f, 20.5f)
            lineTo(16.5f, 19.4f)
            curveTo(16.1f, 19.65f, 15.7f, 19.85f, 15.25f, 19.95f)
            lineTo(14.75f, 22f)
            horizontalLineTo(9.25f)
            lineTo(8.75f, 19.95f)
            curveTo(8.3f, 19.85f, 7.9f, 19.65f, 7.5f, 19.4f)
            lineTo(5f, 20.5f)
            lineTo(3f, 17.5f)
            lineTo(4.6f, 15.6f)
            curveTo(4.57f, 15.4f, 4.57f, 15.2f, 4.6f, 15f)
            lineTo(3f, 13f)
            lineTo(5f, 10f)
            lineTo(7.5f, 11.1f)
            curveTo(7.9f, 10.85f, 8.3f, 10.65f, 8.75f, 10.55f)
            lineTo(9.25f, 8.5f)
            horizontalLineTo(14.75f)
            lineTo(15.25f, 10.55f)
            curveTo(15.7f, 10.65f, 16.1f, 10.85f, 16.5f, 11.1f)
            lineTo(19f, 10f)
            lineTo(21f, 13f)
            close()
        }
    }.build() }

val IconPlus: ImageVector by lazy { ImageVector.Builder(
        name = "Plus",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 5f)
            verticalLineTo(19f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(5f, 12f)
            horizontalLineTo(19f)
        }
    }.build() }

val IconSearch: ImageVector by lazy { ImageVector.Builder(
        name = "Search",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(10f, 17f)
            curveTo(13.87f, 17f, 17f, 13.87f, 17f, 10f)
            curveTo(17f, 6.13f, 13.87f, 3f, 10f, 3f)
            curveTo(6.13f, 3f, 3f, 6.13f, 3f, 10f)
            curveTo(3f, 13.87f, 6.13f, 17f, 10f, 17f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(21f, 21f)
            lineTo(15f, 15f)
        }
    }.build() }

val IconEye: ImageVector by lazy { ImageVector.Builder(
        name = "Eye",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // 眼睛：上下两条弧 + 中间瞳孔
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(2.5f, 12f)
            curveTo(5f, 7f, 8.5f, 4.8f, 12f, 4.8f)
            curveTo(15.5f, 4.8f, 19f, 7f, 21.5f, 12f)
            curveTo(19f, 17f, 15.5f, 19.2f, 12f, 19.2f)
            curveTo(8.5f, 19.2f, 5f, 17f, 2.5f, 12f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 9.3f)
            curveTo(13.5f, 9.3f, 14.7f, 10.5f, 14.7f, 12f)
            curveTo(14.7f, 13.5f, 13.5f, 14.7f, 12f, 14.7f)
            curveTo(10.5f, 14.7f, 9.3f, 13.5f, 9.3f, 12f)
            curveTo(9.3f, 10.5f, 10.5f, 9.3f, 12f, 9.3f)
            close()
        }
    }.build() }

val IconExpandDown: ImageVector by lazy { ImageVector.Builder(
        name = "ExpandDown",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(6f, 9f)
            lineTo(12f, 15f)
            lineTo(18f, 9f)
        }
    }.build() }

val IconCollapseUp: ImageVector by lazy { ImageVector.Builder(
        name = "CollapseUp",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(18f, 15f)
            lineTo(12f, 9f)
            lineTo(6f, 15f)
        }
    }.build() }

val IconAttachment: ImageVector by lazy { ImageVector.Builder(
        name = "Attachment",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(21.44f, 11.05f)
            lineTo(12.25f, 20.24f)
            curveTo(11.12f, 21.37f, 9.46f, 22f, 7.78f, 22f)
            curveTo(6.1f, 22f, 4.44f, 21.37f, 3.31f, 20.24f)
            curveTo(2.18f, 19.11f, 1.55f, 17.45f, 1.55f, 15.77f)
            curveTo(1.55f, 14.09f, 2.18f, 12.43f, 3.31f, 11.3f)
            lineTo(12.5f, 2.11f)
            curveTo(13.28f, 1.33f, 14.36f, 0.89f, 15.5f, 0.89f)
            curveTo(16.64f, 0.89f, 17.72f, 1.33f, 18.5f, 2.11f)
            curveTo(19.28f, 2.89f, 19.72f, 3.97f, 19.72f, 5.11f)
            curveTo(19.72f, 6.25f, 19.28f, 7.33f, 18.5f, 8.11f)
            lineTo(9.41f, 17.2f)
            curveTo(8.98f, 17.63f, 8.38f, 17.87f, 7.75f, 17.87f)
            curveTo(7.12f, 17.87f, 6.52f, 17.63f, 6.09f, 17.2f)
            curveTo(5.66f, 16.77f, 5.42f, 16.17f, 5.42f, 15.54f)
            curveTo(5.42f, 14.91f, 5.66f, 14.31f, 6.09f, 13.88f)
            lineTo(15f, 5f)
        }
    }.build() }

val IconSave: ImageVector by lazy { ImageVector.Builder(
        name = "Save",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(17f, 3f)
            lineTo(5f, 3f)
            curveTo(3.89f, 3f, 3f, 3.9f, 3f, 5f)
            lineTo(3f, 19f)
            curveTo(3f, 20.1f, 3.89f, 21f, 5f, 21f)
            lineTo(19f, 21f)
            curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
            lineTo(21f, 7f)
            lineTo(17f, 3f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 15f)
            curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
            curveTo(15f, 10.34f, 13.66f, 9f, 12f, 9f)
            curveTo(10.34f, 9f, 9f, 10.34f, 9f, 12f)
            curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(5f, 3f)
            lineTo(15f, 3f)
            lineTo(15f, 9f)
            lineTo(5f, 9f)
            close()
        }
    }.build() }

val IconBack: ImageVector by lazy { ImageVector.Builder(
        name = "Back",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(20f, 12f)
            horizontalLineTo(4f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(10f, 18f)
            lineTo(4f, 12f)
            lineTo(10f, 6f)
        }
    }.build() }

val IconClear: ImageVector by lazy { ImageVector.Builder(
        name = "Clear",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(18f, 6f)
            lineTo(6f, 18f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(6f, 6f)
            lineTo(18f, 18f)
        }
    }.build() }

val IconRestore: ImageVector by lazy { ImageVector.Builder(
        name = "Restore",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(13f, 3f)
            curveTo(8.03f, 3f, 4f, 7.03f, 4f, 12f)
            horizontalLineTo(2f)
            lineTo(5.89f, 15.89f)
            lineTo(5.99f, 16.08f)
            lineTo(10f, 12f)
            horizontalLineTo(8f)
            curveTo(8f, 8.13f, 11.13f, 5f, 15f, 5f)
            curveTo(18.87f, 5f, 22f, 8.13f, 22f, 12f)
            curveTo(22f, 15.87f, 18.87f, 19f, 15f, 19f)
            curveTo(13.07f, 19f, 11.32f, 18.21f, 10.06f, 16.94f)
            lineTo(8.64f, 18.36f)
            curveTo(10.29f, 20.01f, 12.52f, 21f, 15f, 21f)
            curveTo(19.97f, 21f, 24f, 16.97f, 24f, 12f)
            curveTo(24f, 7.03f, 19.97f, 3f, 15f, 3f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(15f, 12f)
            verticalLineTo(7f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(15f, 12f)
            lineTo(12f, 14f)
        }
    }.build() }

val IconDelete: ImageVector by lazy { ImageVector.Builder(
        name = "Delete",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 6f)
            horizontalLineTo(21f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(8f, 6f)
            verticalLineTo(4f)
            curveTo(8f, 3.45f, 8.45f, 3f, 9f, 3f)
            horizontalLineTo(15f)
            curveTo(15.55f, 3f, 16f, 3.45f, 16f, 4f)
            verticalLineTo(6f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(10f, 11f)
            verticalLineTo(17f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(14f, 11f)
            verticalLineTo(17f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(19f, 6f)
            lineTo(18f, 20f)
            curveTo(18f, 20.55f, 17.55f, 21f, 17f, 21f)
            horizontalLineTo(7f)
            curveTo(6.45f, 21f, 6f, 20.55f, 6f, 20f)
            lineTo(5f, 6f)
        }
    }.build() }

val IconClearAll: ImageVector by lazy { ImageVector.Builder(
        name = "ClearAll",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 6f)
            horizontalLineTo(21f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(5f, 12f)
            horizontalLineTo(19f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(7f, 18f)
            horizontalLineTo(17f)
        }
    }.build() }

val IconHistory: ImageVector by lazy { ImageVector.Builder(
        name = "History",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 6f)
            verticalLineTo(12f)
            lineTo(16f, 14f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 22f)
            curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
            close()
        }
    }.build() }

val IconRollback: ImageVector by lazy { ImageVector.Builder(
        name = "Rollback",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4f, 12f)
            lineTo(9f, 17f)
            lineTo(9f, 7f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(11f, 12f)
            curveTo(11f, 8.13f, 14.13f, 5f, 18f, 5f)
            curveTo(21.87f, 5f, 25f, 8.13f, 25f, 12f)
            curveTo(25f, 15.87f, 21.87f, 19f, 18f, 19f)
            curveTo(15.07f, 19f, 12.54f, 17.23f, 11.42f, 14.71f)
        }
    }.build() }

val IconCamera: ImageVector by lazy { ImageVector.Builder(
        name = "Camera",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(23f, 19f)
            curveTo(23f, 19.55f, 22.55f, 20f, 22f, 20f)
            horizontalLineTo(2f)
            curveTo(1.45f, 20f, 1f, 19.55f, 1f, 19f)
            verticalLineTo(8f)
            curveTo(1f, 7.45f, 1.45f, 7f, 2f, 7f)
            horizontalLineTo(6f)
            lineTo(8f, 5f)
            horizontalLineTo(16f)
            lineTo(18f, 7f)
            horizontalLineTo(22f)
            curveTo(22.55f, 7f, 23f, 7.45f, 23f, 8f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 17f)
            curveTo(14.21f, 17f, 16f, 15.21f, 16f, 13f)
            curveTo(16f, 10.79f, 14.21f, 9f, 12f, 9f)
            curveTo(9.79f, 9f, 8f, 10.79f, 8f, 13f)
            curveTo(8f, 15.21f, 9.79f, 17f, 12f, 17f)
            close()
        }
    }.build() }

val IconAudio: ImageVector by lazy { ImageVector.Builder(
        name = "Audio",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 2f)
            curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
            verticalLineTo(13f)
            curveTo(9f, 14.66f, 10.34f, 16f, 12f, 16f)
            curveTo(13.66f, 16f, 15f, 14.66f, 15f, 13f)
            verticalLineTo(5f)
            curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(19f, 10f)
            verticalLineTo(13f)
            curveTo(19f, 16.87f, 15.87f, 20f, 12f, 20f)
            curveTo(8.13f, 20f, 5f, 16.87f, 5f, 13f)
            verticalLineTo(10f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 20f)
            verticalLineTo(22f)
        }
    }.build() }

val IconGallery: ImageVector by lazy { ImageVector.Builder(
        name = "Gallery",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(21f, 19f)
            curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
            horizontalLineTo(5f)
            curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
            verticalLineTo(5f)
            curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
            horizontalLineTo(19f)
            curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(8f, 10f)
            curveTo(9.1f, 10f, 10f, 9.1f, 10f, 8f)
            curveTo(10f, 6.9f, 9.1f, 6f, 8f, 6f)
            curveTo(6.9f, 6f, 6f, 6.9f, 6f, 8f)
            curveTo(6f, 9.1f, 6.9f, 10f, 8f, 10f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(21f, 17f)
            lineTo(16f, 12f)
            lineTo(5f, 21f)
        }
    }.build() }

val IconEssay: ImageVector by lazy { ImageVector.Builder(
        name = "Essay",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(14f, 2f)
            horizontalLineTo(6f)
            curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
            verticalLineTo(20f)
            curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
            horizontalLineTo(18f)
            curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
            verticalLineTo(8f)
            lineTo(14f, 2f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(8f, 13f)
            horizontalLineTo(16f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(8f, 17f)
            horizontalLineTo(16f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(8f, 9f)
            horizontalLineTo(10f)
        }
    }.build() }

val IconDialogue: ImageVector by lazy { ImageVector.Builder(
        name = "Dialogue",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(21f, 15f)
            curveTo(21f, 16.1f, 20.1f, 17f, 19f, 17f)
            horizontalLineTo(7f)
            lineTo(3f, 21f)
            verticalLineTo(5f)
            curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
            horizontalLineTo(19f)
            curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(8f, 9f)
            horizontalLineTo(16f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(8f, 13f)
            horizontalLineTo(13f)
        }
    }.build() }

val IconLock: ImageVector by lazy { ImageVector.Builder(
        name = "Lock",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(18f, 8f)
            horizontalLineTo(6f)
            curveTo(4.9f, 8f, 4f, 8.9f, 4f, 10f)
            verticalLineTo(20f)
            curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
            horizontalLineTo(18f)
            curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
            verticalLineTo(10f)
            curveTo(20f, 8.9f, 19.1f, 8f, 18f, 8f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 17f)
            curveTo(13.1f, 17f, 14f, 16.1f, 14f, 15f)
            curveTo(14f, 13.9f, 13.1f, 13f, 12f, 13f)
            curveTo(10.9f, 13f, 10f, 13.9f, 10f, 15f)
            curveTo(10f, 16.1f, 10.9f, 17f, 12f, 17f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(8f, 8f)
            verticalLineTo(6f)
            curveTo(8f, 3.79f, 9.79f, 2f, 12f, 2f)
            curveTo(14.21f, 2f, 16f, 3.79f, 16f, 6f)
            verticalLineTo(8f)
        }
    }.build() }

val IconExport: ImageVector by lazy { ImageVector.Builder(
        name = "Export",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(21f, 15f)
            verticalLineTo(19f)
            curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
            horizontalLineTo(5f)
            curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
            verticalLineTo(15f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(7f, 10f)
            lineTo(12f, 15f)
            lineTo(17f, 10f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 15f)
            verticalLineTo(3f)
        }
    }.build() }

val IconImport: ImageVector by lazy { ImageVector.Builder(
        name = "Import",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(21f, 15f)
            verticalLineTo(19f)
            curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
            horizontalLineTo(5f)
            curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
            verticalLineTo(15f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(17f, 10f)
            lineTo(12f, 5f)
            lineTo(7f, 10f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 5f)
            verticalLineTo(15f)
        }
    }.build() }

val IconEmptyRecords: ImageVector by lazy { ImageVector.Builder(
        name = "EmptyRecords",
        defaultWidth = 64.dp,
        defaultHeight = 64.dp,
        viewportWidth = 64f,
        viewportHeight = 64f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(16f, 24f)
            horizontalLineTo(48f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(16f, 32f)
            horizontalLineTo(44f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(16f, 40f)
            horizontalLineTo(38f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(16f, 48f)
            horizontalLineTo(30f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 56f)
            lineTo(12f, 8f)
            lineTo(52f, 8f)
            lineTo(52f, 56f)
            close()
        }
    }.build() }

val IconEmptySearch: ImageVector by lazy { ImageVector.Builder(
        name = "EmptySearch",
        defaultWidth = 64.dp,
        defaultHeight = 64.dp,
        viewportWidth = 64f,
        viewportHeight = 64f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(28f, 44f)
            curveTo(36.84f, 44f, 44f, 36.84f, 44f, 28f)
            curveTo(44f, 19.16f, 36.84f, 12f, 28f, 12f)
            curveTo(19.16f, 12f, 12f, 19.16f, 12f, 28f)
            curveTo(12f, 36.84f, 19.16f, 44f, 28f, 44f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(52f, 52f)
            lineTo(40f, 40f)
        }
    }.build() }

val IconEmptyTrash: ImageVector by lazy { ImageVector.Builder(
        name = "EmptyTrash",
        defaultWidth = 64.dp,
        defaultHeight = 64.dp,
        viewportWidth = 64f,
        viewportHeight = 64f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(16f, 20f)
            horizontalLineTo(48f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(24f, 20f)
            verticalLineTo(14f)
            curveTo(24f, 12.9f, 24.9f, 12f, 26f, 12f)
            horizontalLineTo(38f)
            curveTo(39.1f, 12f, 40f, 12.9f, 40f, 14f)
            verticalLineTo(20f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(28f, 30f)
            verticalLineTo(44f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(36f, 30f)
            verticalLineTo(44f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(20f, 20f)
            lineTo(22f, 52f)
            curveTo(22f, 53.1f, 22.9f, 54f, 24f, 54f)
            horizontalLineTo(40f)
            curveTo(41.1f, 54f, 42f, 53.1f, 42f, 52f)
            lineTo(44f, 20f)
        }
    }.build() }

val IconSelectAll: ImageVector by lazy { ImageVector.Builder(name = "SelectAll", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(9f, 11f); lineTo(12f, 14f); lineTo(22f, 4f) }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(21f, 12f); verticalLineTo(19f); curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f); horizontalLineTo(5f); curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f); verticalLineTo(5f); curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f); horizontalLineTo(16f) }
    }.build() }

val IconDeselectAll: ImageVector by lazy { ImageVector.Builder(name = "DeselectAll", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(3f, 3f); horizontalLineTo(21f); verticalLineTo(21f); horizontalLineTo(3f); close() }
    }.build() }

val IconClose: ImageVector by lazy { ImageVector.Builder(name = "Close", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(18f, 6f); lineTo(6f, 18f) }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(6f, 6f); lineTo(18f, 18f) }
    }.build() }

val IconConcept: ImageVector by lazy { ImageVector.Builder(name = "Concept", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(12f, 2f); lineTo(2f, 7f); lineTo(12f, 12f); lineTo(22f, 7f); close() }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(2f, 17f); lineTo(12f, 22f); lineTo(22f, 17f) }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(2f, 12f); lineTo(12f, 17f); lineTo(22f, 12f) }
    }.build() }

/** 分类（父项=组织壳）：标签形，表达"归类/收纳"；非概念节点立方体。 */
val IconCategory: ImageVector by lazy { ImageVector.Builder(
    name = "Category",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    // 标签主体（右侧指向的尖角）
    path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(3f, 5f)
        lineTo(13f, 5f)
        curveTo(14.1f, 5f, 15f, 5.9f, 15f, 7f)
        lineTo(20f, 12f)
        lineTo(15f, 17f)
        curveTo(15f, 18.1f, 14.1f, 19f, 13f, 19f)
        lineTo(3f, 19f)
        curveTo(2.9f, 19f, 2f, 18.1f, 2f, 17f)
        lineTo(2f, 7f)
        curveTo(2f, 5.9f, 2.9f, 5f, 3f, 5f)
        close()
    }
    // 标签孔
    path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(6.5f, 12f)
        curveTo(6.5f, 10.9f, 7.4f, 10f, 8.5f, 10f)
        curveTo(9.6f, 10f, 10.5f, 10.9f, 10.5f, 12f)
        curveTo(10.5f, 13.1f, 9.6f, 14f, 8.5f, 14f)
        curveTo(7.4f, 14f, 6.5f, 13.1f, 6.5f, 12f)
        close()
    }
}.build() }

val IconGraph: ImageVector by lazy { ImageVector.Builder(name = "Graph", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(4f, 4f); lineTo(20f, 10f); lineTo(10f, 20f); lineTo(4f, 4f) }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(4f, 1f); lineTo(7f, 4f); lineTo(4f, 7f); lineTo(1f, 4f); close() }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(20f, 7f); lineTo(23f, 10f); lineTo(20f, 13f); lineTo(17f, 10f); close() }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(10f, 17f); lineTo(13f, 20f); lineTo(10f, 23f); lineTo(7f, 20f); close() }
    }.build() }

val IconEmotion: ImageVector by lazy { ImageVector.Builder(name = "Emotion", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(12f, 2f); curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f); curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f); curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f); curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f); close() }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(8f, 14f); curveTo(8f, 14f, 10f, 16f, 12f, 16f); curveTo(14f, 16f, 16f, 14f, 16f, 14f) }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(9f, 9f); horizontalLineTo(9.01f) }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(15f, 9f); horizontalLineTo(15.01f) }
    }.build() }

val IconChart: ImageVector by lazy { ImageVector.Builder(name = "Chart", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(4f, 20f); horizontalLineTo(20f) }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(6f, 16f); verticalLineTo(12f) }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(10f, 16f); verticalLineTo(8f) }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(14f, 16f); verticalLineTo(10f) }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(18f, 16f); verticalLineTo(6f) }
    }.build() }

val IconTrendChart: ImageVector by lazy { ImageVector.Builder(name = "TrendChart", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        // 坐标轴（L 形）
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(4f, 4f); verticalLineTo(20f); horizontalLineTo(20f) }
        // 折线（与柱状图的 IconChart 成对：那个是"构成"，这个是"走势"）
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(7f, 15.5f); lineTo(11f, 10.5f); lineTo(14.5f, 13f); lineTo(19f, 7f) }
    }.build() }

val IconWarning: ImageVector by lazy { ImageVector.Builder(name = "Warning", defaultWidth = 14.dp, defaultHeight = 14.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(12f, 2f); lineTo(2f, 21f); lineTo(22f, 21f); close() }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(12f, 10f); lineTo(12f, 15f) }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { moveTo(12f, 18f); lineTo(12f, 19f) }
    }.build() }

val IconCleanup: ImageVector by lazy { ImageVector.Builder(
        name = "Cleanup",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // 菱形外框（象征清洁/精炼）
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 3f); lineTo(21f, 12f); lineTo(12f, 21f); lineTo(3f, 12f); close()
        }
        // 内部对勾（清算完成）
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(9f, 12f); lineTo(11f, 15f); lineTo(15f, 9f)
        }
    }.build() }

val IconSystemInstruction: ImageVector by lazy { ImageVector.Builder(
        name = "SystemInstruction",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // 文档外框（圆角矩形）
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 4f)
            curveTo(3f, 3.45f, 3.45f, 3f, 4f, 3f)
            lineTo(20f, 3f)
            curveTo(20.55f, 3f, 21f, 3.45f, 21f, 4f)
            lineTo(21f, 20f)
            curveTo(21f, 20.55f, 20.55f, 21f, 20f, 21f)
            lineTo(4f, 21f)
            curveTo(3.45f, 21f, 3f, 20.55f, 3f, 20f)
            close()
        }
        // 左上角四角星（Sparkle）
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(7f, 5f)
            lineTo(8f, 8f)
            lineTo(11f, 9f)
            lineTo(8f, 10f)
            lineTo(7f, 13f)
            lineTo(6f, 10f)
            lineTo(3f, 9f)
            lineTo(6f, 8f)
            close()
        }
        // 文本行 1（星右侧）
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(13f, 7f)
            lineTo(19f, 7f)
        }
        // 文本行 2
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(7f, 14f)
            lineTo(19f, 14f)
        }
        // 文本行 3
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(7f, 18f)
            lineTo(16f, 18f)
        }
    }.build() }

val IconTutorial: ImageVector by lazy { ImageVector.Builder(
        name = "Tutorial",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // 圆环外框（帮助/教程）
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 3f)
            curveTo(7.03f, 3f, 3f, 7.03f, 3f, 12f)
            curveTo(3f, 16.97f, 7.03f, 21f, 12f, 21f)
            curveTo(16.97f, 21f, 21f, 16.97f, 21f, 12f)
            curveTo(21f, 7.03f, 16.97f, 3f, 12f, 3f)
            close()
        }
        // 问号主体
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(9.09f, 9f)
            curveTo(9.37f, 7.05f, 11.4f, 5.68f, 13.44f, 6.34f)
            curveTo(15.48f, 7f, 16.6f, 9.08f, 16f, 11.07f)
            curveTo(15.5f, 12.72f, 13.9f, 13.66f, 12f, 13.5f)
            curveTo(12f, 14.5f, 12f, 15f, 12f, 15.5f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 17f)
            lineTo(12.01f, 17f)
        }
    }.build() }

val IconAlbum: ImageVector by lazy { ImageVector.Builder(
        name = "Album",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // 翻开的两页：左页放照片，右页放文字
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3.4f, 4.2f)
            lineTo(11.9f, 4.2f)
            lineTo(11.9f, 19.8f)
            lineTo(3.4f, 19.8f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12.1f, 4.2f)
            lineTo(20.6f, 4.2f)
            lineTo(20.6f, 19.8f)
            lineTo(12.1f, 19.8f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 4.2f)
            lineTo(12f, 19.8f)
        }
        // 左页照片框
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(5.1f, 6.6f)
            lineTo(10.2f, 6.6f)
            lineTo(10.2f, 10.6f)
            lineTo(5.1f, 10.6f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(7f, 7.2f)
            curveTo(7.8f, 7.2f, 8.5f, 7.9f, 8.5f, 8.7f)
            curveTo(8.5f, 9.5f, 7.8f, 10.2f, 7f, 10.2f)
            curveTo(6.2f, 10.2f, 5.5f, 9.5f, 5.5f, 8.7f)
            curveTo(5.5f, 7.9f, 6.2f, 7.2f, 7f, 7.2f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(5.1f, 10.4f)
            lineTo(7.2f, 8.2f)
            lineTo(8.4f, 9.6f)
            lineTo(9.4f, 8.7f)
            lineTo(10.2f, 10.4f)
        }
        // 右页文字行
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(13.8f, 7f)
            lineTo(18.7f, 7f)
            moveTo(13.8f, 9.6f)
            lineTo(16.9f, 9.6f)
            moveTo(13.8f, 12.2f)
            lineTo(18.7f, 12.2f)
        }
    }.build() }

/**
 * 「来源草稿」图标 —— **双向连接**（用户 2026-10-11 亲手画的形状）：
 *
 * ```
 *   →
 * |     |
 *   ←
 * ```
 *
 * 两条竖杠 + 一右一左两个箭头 = 记录与草稿之间是**双向**的（这块转录成了那条记录、
 * 那条记录能回到这块）。与其它内置图标同一套画法：24 视窗、纯描边、随 `Icon(tint=…)` 着色，
 * 所以 14dp 下也清楚；**颜色跟同排图标一致**（灰），不要在调用处给它上主题色。
 */
val IconTranscribe: ImageVector by lazy { ImageVector.Builder(
        name = "Transcribe",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // 左竖杠
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(5.2f, 6.4f)
            lineTo(5.2f, 17.6f)
        }
        // 右竖杠
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(18.8f, 6.4f)
            lineTo(18.8f, 17.6f)
        }
        // 上箭头：向右（连带箭头两撇）
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(8.6f, 9.4f)
            lineTo(15.2f, 9.4f)
            moveTo(12.9f, 7.1f)
            lineTo(15.2f, 9.4f)
            lineTo(12.9f, 11.7f)
        }
        // 下箭头：向左
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(15.2f, 14.6f)
            lineTo(8.6f, 14.6f)
            moveTo(10.9f, 12.3f)
            lineTo(8.6f, 14.6f)
            lineTo(10.9f, 16.9f)
        }
    }.build() }
/**
 * 表盘：一圈虚线的钟面 + 一根指向 12 的刻度。
 * 语义是"框"——一段时间的边界，不是时间本身。
 */
val IconDialFrame: ImageVector by lazy { ImageVector.Builder(
        name = "DialFrame",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
            // 外圈（虚线段：上下左右四段弧，靠留空形成虚线感）
            moveTo(12f, 3f)
            curveTo(16.97f, 3f, 21f, 7.03f, 21f, 12f)
            curveTo(21f, 16.97f, 16.97f, 21f, 12f, 21f)
            curveTo(7.03f, 21f, 3f, 16.97f, 3f, 12f)
            curveTo(3f, 7.03f, 7.03f, 3f, 12f, 3f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
            // 12 点与 6 点的两个刻度，提示"这是表盘不是圆"
            moveTo(12f, 5.4f)
            lineTo(12f, 7.2f)
            moveTo(12f, 16.8f)
            lineTo(12f, 18.6f)
        }
    }.build() }

/**
 * 咬紧秒针：一根秒针，被上下两排牙齿咬住（不想让这一秒过去）。
 * 语义是"定格当下"——所以针在中间、牙从两侧合拢。
 */
val IconBiteSecond: ImageVector by lazy { ImageVector.Builder(
        name = "BiteSecond",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // 秒针（竖线 + 尾部小圆点）
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
            moveTo(12f, 3.5f)
            lineTo(12f, 20.5f)
        }
        // 上排牙（朝下咬）
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4.5f, 9.5f)
            lineTo(7f, 9.5f)
            lineTo(8.5f, 12f)
            lineTo(10f, 9.5f)
            lineTo(11.4f, 9.5f)
        }
        // 下排牙（朝上咬）
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4.5f, 14.5f)
            lineTo(7f, 14.5f)
            lineTo(8.5f, 12f)
            lineTo(10f, 14.5f)
            lineTo(11.4f, 14.5f)
        }
        // 右侧同构的两排牙（镜像）
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(19.5f, 9.5f)
            lineTo(17f, 9.5f)
            lineTo(15.5f, 12f)
            lineTo(14f, 9.5f)
            lineTo(12.6f, 9.5f)
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(19.5f, 14.5f)
            lineTo(17f, 14.5f)
            lineTo(15.5f, 12f)
            lineTo(14f, 14.5f)
            lineTo(12.6f, 14.5f)
        }
    }.build() }
/**
 * 「语义」开关图标：两个相交的圆（Venn）——表示"相似/交集"，
 * 比魔法棒更贴合"语义检索"这件事，也不用引入 emoji。
 */
val IconSemantic: ImageVector by lazy { ImageVector.Builder(
        name = "Semantic",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f) {
            // 左圆
            moveTo(15f, 12f)
            curveTo(15f, 15.31f, 12.31f, 18f, 9f, 18f)
            curveTo(5.69f, 18f, 3f, 15.31f, 3f, 12f)
            curveTo(3f, 8.69f, 5.69f, 6f, 9f, 6f)
            curveTo(12.31f, 6f, 15f, 8.69f, 15f, 12f)
            close()
        }
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f) {
            // 右圆
            moveTo(21f, 12f)
            curveTo(21f, 15.31f, 18.31f, 18f, 15f, 18f)
            curveTo(11.69f, 18f, 9f, 15.31f, 9f, 12f)
            curveTo(9f, 8.69f, 11.69f, 6f, 15f, 6f)
            curveTo(18.31f, 6f, 21f, 8.69f, 21f, 12f)
            close()
        }
    }.build() }

/**
 * 札记（2026-11-14）：一张纸 + 一道折角。和 IconAnchor 同一族——
 * 24dp 视框、2f 描边、圆头圆角，没有填充。
 */
val IconZoneNote: ImageVector by lazy { ImageVector.Builder(
    name = "ZoneNote", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(6f, 3f)
        lineTo(14.5f, 3f)
        lineTo(19f, 7.5f)
        lineTo(19f, 21f)
        lineTo(6f, 21f)
        close()
    }
    path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(14.5f, 3f)
        lineTo(14.5f, 7.5f)
        lineTo(19f, 7.5f)
    }
    path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(9f, 12.5f)
        lineTo(16f, 12.5f)
    }
    path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(9f, 16.5f)
        lineTo(13.5f, 16.5f)
    }
}.build() }

/**
 * 自定义分区（2026-11-14）：一个方框套一个小点，表示"自己划出来的一块地"。
 * 同一族：24dp / 2f / 圆头圆角 / 无填充。
 */
val IconZoneCustom: ImageVector by lazy { ImageVector.Builder(
    name = "ZoneCustom", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4f, 6f)
        lineTo(4f, 20f)
        lineTo(20f, 20f)
        lineTo(20f, 6f)
        close()
    }
    path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4f, 10.5f)
        lineTo(20f, 10.5f)
    }
    path(fill = SolidColor(Color.Black), stroke = SolidColor(Color.Unspecified), strokeLineWidth = 0f) {
        moveTo(8f, 15.2f)
        lineTo(11f, 15.2f)
        lineTo(11f, 17.2f)
        lineTo(8f, 17.2f)
        close()
    }
}.build() }

/**
 * 预设（2026-11-17）：书签样式 —— "把当前这套选项存下来"。
 *
 * 24 视口、2f 描边、圆角笔头，和 [IconBack] 同族；渲染时按顶栏动作给 22dp。
 * 不填色：项目里的图标都是描边（2.2 条：不用实心图标）。
 */
val IconPreset: ImageVector by lazy { ImageVector.Builder(
        name = "Preset",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Unspecified), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(7f, 3.5f)
            lineTo(17f, 3.5f)
            lineTo(17f, 20f)
            lineTo(12f, 15.8f)
            lineTo(7f, 20f)
            close()
        }
    }.build() }

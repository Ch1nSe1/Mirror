package memore.app.data.local

import androidx.room.TypeConverter
import memore.app.data.model.AnalysisData
import memore.app.data.model.RecordType
import memore.app.utils.GsonInstance

class Converters {

    @TypeConverter
    fun fromRecordType(value: RecordType): String = value.name

    @TypeConverter
    fun toRecordType(value: String): RecordType = enumValues<RecordType>().find { it.name == value } ?: RecordType.ESSAY

    @TypeConverter
    fun fromAnalysisData(value: AnalysisData?): String? {
        return value?.let { GsonInstance.gson.toJson(it) }
    }

    @TypeConverter
    fun toAnalysisData(value: String?): AnalysisData? {
        return value?.takeIf { it.isNotBlank() }?.let {
            try { GsonInstance.gson.fromJson(it, AnalysisData::class.java) } catch (_: Exception) { null }
        }
    }
}

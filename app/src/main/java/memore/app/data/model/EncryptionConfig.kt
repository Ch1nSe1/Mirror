package memore.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "encryption_config")
data class EncryptionConfig(
    @PrimaryKey
    val id: Int = 1,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    @ColumnInfo(name = "iterations")
    val iterations: Int = 50_000,

    /**
     * 加密方案版本：
     * 1 = 旧格式（盐值为 hex 字符串的 ASCII 字节、密保问题哈希用固定盐）
     * 2 = 新格式（盐值为 Base64 编码的原始随机字节、密保问题哈希用随机盐）
     * 未设置时默认 1（DB 迁移默认值），新写入配置一律为 2。
     */
    @ColumnInfo(name = "enc_version")
    val encVersion: Int = 1,

    @ColumnInfo(name = "password_salt")
    val passwordSalt: String,

    @ColumnInfo(name = "password_hash")
    val passwordHash: String,

    @ColumnInfo(name = "key_salt")
    val keySalt: String,

    @ColumnInfo(name = "question")
    val question: String,

    @ColumnInfo(name = "question_hash")
    val questionHash: String,

    /** 密保问题哈希的盐（Base64 原始字节，v2 起；旧配置为空字符串回退固定盐）。 */
    @ColumnInfo(name = "question_salt")
    val questionSalt: String = "",

    @ColumnInfo(name = "answer_salt")
    val answerSalt: String,

    @ColumnInfo(name = "answer_hash")
    val answerHash: String,

    @ColumnInfo(name = "wrapped_key")
    val wrappedKey: String = "",

    /**
     * v3：**用密码派生的密钥（KEK）包裹的主密钥**。
     *
     * v2 的内容密钥是每次从密码现算的（`PBKDF2(密码, keySalt)`）→ 换密码必换密钥 → 必须重加密全部内容。
     * v3 改为"主密钥只确定一次、此后永不改变"，换密码只重新包裹本字段 → **内容零改动**。
     *
     * 空字符串 = 尚未迁到 v3（此时 [encVersion] 为 1 或 2，密钥仍由密码现算）。
     */
    @ColumnInfo(name = "wrapped_key_pw")
    val wrappedKeyPw: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

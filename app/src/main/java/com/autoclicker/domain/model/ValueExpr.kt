package com.autoclicker.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 可用于参数/变量赋值的取值表达式。 */
@Serializable
sealed interface ValueExpr

/** 字面量（数字或文本，运行时按需解析）。 */
@Serializable
@SerialName("literal")
data class LiteralValue(val text: String) : ValueExpr

/** 引用变量。 */
@Serializable
@SerialName("variable")
data class VariableRef(val name: String) : ValueExpr

/** 随机整数 [from, to]。 */
@Serializable
@SerialName("randomNumber")
data class RandomNumber(val from: Long, val to: Long) : ValueExpr

/** 当前时间，按 format 格式化。 */
@Serializable
@SerialName("time")
data class TimeValue(val format: String = "yyyy-MM-dd HH:mm:ss") : ValueExpr

/** 变量类型（新建变量时选择）。 */
enum class VarType {
    NUMBER,
    TEXT,
    RANDOM_NUMBER,
    RANDOM_TEXT,
    TIME,
    FIXED_TIME,
    COORDINATE,
}

/** 函数包入参定义。 */
@Serializable
data class ParamDef(
    val name: String,
    val type: VarType = VarType.TEXT,
    val defaultValue: String = "",
    val description: String = "",
)

/** 函数包返回值定义。 */
@Serializable
data class ReturnDef(
    val name: String,
    val description: String = "",
)

/** 文本组：一批文本可被「输入文字-文本组」按顺序或随机取用。 */
@Serializable
data class TextGroup(
    val id: String,
    val name: String,
    val lines: List<String> = emptyList(),
)
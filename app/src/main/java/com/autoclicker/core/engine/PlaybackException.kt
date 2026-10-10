package com.autoclicker.core.engine

/**
 * 回放执行异常。
 *
 * 手势派发失败、函数包缺失/调用超深/环、脚本数据非法等情况下抛出，
 * 消息为中文，调用方应使引擎回到空闲态并向用户提示。
 */
class PlaybackException(message: String) : Exception(message)

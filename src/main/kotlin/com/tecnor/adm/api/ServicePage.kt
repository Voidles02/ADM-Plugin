package com.tecnor.adm.api

data class ServicePage<T>(val page: Int, val pages: Int, val rows: List<T>)
class ServiceDenied(val result: ActionResult) : RuntimeException(result.messageKey())
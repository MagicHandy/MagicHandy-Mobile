package com.magichandy.mobile.core.api

/** A UTF-8 plain-text attachment. The core generates the basename and redacts the contents. */
public data class DiagnosticsReport(public val fileName: String, public val text: String)

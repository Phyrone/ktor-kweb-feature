package de.phyrone.kwebfeature

import io.kweb.Server2ClientMessage

interface AbstractKweb {
    val appliedPlugins: Set<io.kweb.plugins.KwebPlugin>
    val plugins: List<io.kweb.plugins.KwebPlugin>

    fun catchOutbound(f: () -> Unit): List<String>
    fun execute(clientId: String, javascript: String)
    fun evaluate(clientId: String, expression: String, handler: (String) -> Unit)
    fun send(clientId: String, instructions: List<Server2ClientMessage.Instruction>)
    fun executeWithCallback(clientId: String, javascript: String, callbackId: Int, handler: (String) -> Unit)
    fun removeCallback(clientId: String, callbackId: Int)
    fun isCatchingOutbound(): Boolean
    fun isNotCatchingOutbound(): Boolean

}
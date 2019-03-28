package de.phyrone.kwebfeature

import com.github.salomonbrys.kotson.fromJson
import io.ktor.application.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import io.ktor.request.uri
import io.ktor.response.respondText
import io.ktor.routing.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.Jetty
import io.ktor.util.AttributeKey
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import io.kweb.*
import io.kweb.browserConnection.KwebClientConnection
import io.kweb.dom.element.creation.tags.button
import io.kweb.dom.element.events.on
import io.kweb.dom.element.events.onImmediate
import io.kweb.dom.element.new
import io.kweb.plugins.KwebPlugin
import mu.KotlinLogging
import org.apache.commons.io.IOUtils
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

typealias LogError = Boolean

typealias JavaScriptError = String

val logger = KotlinLogging.logger {}

class KwebFeature(val conf: Configuration) : AbstractKweb {


    override val plugins: List<KwebPlugin>
        get() = conf.plugins
    // TODO: This should be exposed via some diagnostic tool
    private val javascriptProfilingStackCounts = if (conf.profileJavascript)
        ConcurrentHashMap<List<StackTraceElement>, AtomicInteger>() else null
    // private val server: Any
    internal val clientState: ConcurrentHashMap<String, RemoteClientState> = java.util.concurrent.ConcurrentHashMap()
    private val mutableAppliedPlugins: MutableSet<io.kweb.plugins.KwebPlugin> = java.util.HashSet()
    override val appliedPlugins: Set<io.kweb.plugins.KwebPlugin> get() = mutableAppliedPlugins
    private val outboundMessageCatcher: ThreadLocal<MutableList<String>?> = ThreadLocal.withInitial { null }
    val startHeadBuilder = StringBuilder()
    val endHeadBuilder = StringBuilder()
    val resourceStream = Kweb::class.java.getResourceAsStream("kweb_bootstrap.html")
    val bootstrapHtmlTemplate = IOUtils.toString(resourceStream, Charsets.UTF_8)
            .replace("<!-- START HEADER PLACEHOLDER -->", startHeadBuilder.toString())
            .replace("<!-- END HEADER PLACEHOLDER -->", endHeadBuilder.toString())

    override fun isCatchingOutbound(): Boolean = outboundMessageCatcher.get() != null
    override fun isNotCatchingOutbound(): Boolean = isCatchingOutbound().not()
    override fun catchOutbound(f: () -> Unit): List<String> {
        require(outboundMessageCatcher.get() == null) { "Can't nest withThreadLocalOutboundMessageCatcher()" }

        val jsList = ArrayList<String>()
        outboundMessageCatcher.set(jsList)
        f()
        outboundMessageCatcher.set(null)
        return jsList
    }

    override fun execute(clientId: String, javascript: String) {
        recordForProfiling()
        val wsClientData = clientState.get(clientId) ?: throw RuntimeException("Client id $clientId not found")
        wsClientData.lastModified = Instant.now()
        val debugToken: String? = if (!conf.debug) null else {
            val dt = Math.abs(random.nextLong()).toString(16)
            wsClientData.debugTokens.put(dt, DebugInfo(javascript, "executing", Throwable()))
            dt
        }
        val outboundMessageCatcher = outboundMessageCatcher.get()
        if (outboundMessageCatcher == null) {
            wsClientData.send(Server2ClientMessage(yourId = clientId, debugToken = debugToken, execute = Server2ClientMessage.Execute(javascript)))
        } else {
            logger.debug("Temporarily storing message for $clientId in threadloacal outboundMessageCatcher")
            outboundMessageCatcher.add(javascript)
        }
    }

    fun send(clientId: String, instruction: Server2ClientMessage.Instruction) = send(clientId, listOf(instruction))
    override fun send(clientId: String, instructions: List<Server2ClientMessage.Instruction>) {
        if (outboundMessageCatcher.get() != null) {
            throw RuntimeException("""
                Can't send instruction because there is an outboundMessageCatcher.  You should check for this with
                """.trimIndent())
        }
        val wsClientData = clientState.get(clientId) ?: throw RuntimeException("Client id $clientId not found")
        wsClientData.lastModified = Instant.now()
        val debugToken: String? = if (!conf.debug) null else {
            val dt = Math.abs(random.nextLong()).toString(16)
            wsClientData.debugTokens.put(dt, DebugInfo(instructions.toString(), "instructions", Throwable()))
            dt
        }
        wsClientData.send(Server2ClientMessage(yourId = clientId, instructions = instructions, debugToken = debugToken))
    }

    override fun executeWithCallback(clientId: String, javascript: String, callbackId: Int, handler: (String) -> Unit) {
        // TODO: Should return handle which can be used for cleanup of event listeners
        val wsClientData = clientState.get(clientId) ?: throw RuntimeException("Client id $clientId not found")
        val debugToken: String? = if (!conf.debug) null else {
            val dt = Math.abs(random.nextLong()).toString(16)
            wsClientData.debugTokens.put(dt, DebugInfo(javascript, "executing with callback", Throwable()))
            dt
        }
        wsClientData.handlers.put(callbackId, handler)
        wsClientData.send(Server2ClientMessage(yourId = clientId, debugToken = debugToken, execute = Server2ClientMessage.Execute(javascript)))
    }

    override fun removeCallback(clientId: String, callbackId: Int) {
        clientState[clientId]?.handlers?.remove(callbackId)
    }

    internal fun handleError(error: Client2ServerMessage.ErrorMessage, remoteClientState: RemoteClientState) {
        val debugInfo = remoteClientState.debugTokens[error.debugToken]
                ?: throw RuntimeException("DebugInfo message not found")
        val logStatementBuilder = StringBuilder()
        logStatementBuilder.appendln("JavaScript message: '${error.error.message}'")
        logStatementBuilder.appendln("Caused by ${debugInfo.action}: '${debugInfo.js}':")
        // TODO: Filtering the stacktrace like this seems a bit kludgy, although I can't think
        // TODO: of a specific reason why it would be bad.
        debugInfo.throwable.stackTrace.pruneAndDumpStackTo(logStatementBuilder)
        if (conf.onError(debugInfo.throwable.stackTrace.toList(), error.error.message)) {
            io.kweb.logger.error(logStatementBuilder.toString())
        }
    }

    override fun evaluate(clientId: String, expression: String, handler: (String) -> Unit) {
        recordForProfiling()
        val wsClientData = clientState.get(clientId)
                ?: throw RuntimeException("Failed to evaluate JavaScript because client id $clientId not found")
        val debugToken: String? = if (!conf.debug) null else {
            val dt = Math.abs(random.nextLong()).toString(16)
            wsClientData.debugTokens.put(dt, DebugInfo(expression, "evaluating", Throwable()))
            dt
        }
        val callbackId = Math.abs(random.nextInt())
        wsClientData.handlers.put(callbackId, handler)
        wsClientData.send(Server2ClientMessage(yourId = clientId, evaluate = Server2ClientMessage.Evaluate(expression, callbackId), debugToken = debugToken))
    }

    private fun cleanUpOldClientStates() {
        val now = Instant.now()
        val toRemove = clientState.entries.mapNotNull { (id: String, state: RemoteClientState) ->
            if (Duration.between(state.lastModified, now) > conf.clientStateTimeout) {
                id
            } else {
                null
            }
        }
        if (toRemove.isNotEmpty()) {
            logger.info("Cleaning up client states for ids: $toRemove")
        }
        for (id in toRemove) {
            clientState.remove(id)
        }
    }

    private fun recordForProfiling() {
        if (javascriptProfilingStackCounts != null && this.outboundMessageCatcher.get() == null) {
            javascriptProfilingStackCounts.computeIfAbsent(Thread.currentThread().stackTrace.slice(3..7), { AtomicInteger(0) }).incrementAndGet()
        }
    }

    class Configuration {
        var debug: Boolean = true
        var refreshPageOnHotswap: Boolean = false
        var plugins: List<io.kweb.plugins.KwebPlugin> = java.util.Collections.emptyList()
        var appServerConfigurator: (io.ktor.routing.Routing) -> Unit = {}
        var onError: ((List<StackTraceElement>, io.kweb.JavaScriptError) -> io.kweb.LogError) = { _, _ -> true }
        var maxPageBuildTimeMS: Long = 500
        var clientStateTimeout: Duration = Duration.ofHours(1)
        var profileJavascript: Boolean = false
    }

    companion object Feature : ApplicationFeature<ApplicationCallPipeline, KwebFeature.Configuration, KwebFeature> {
        override val key: AttributeKey<KwebFeature> = AttributeKey("KwebFeature")

        override fun install(pipeline: ApplicationCallPipeline, configure: Configuration.() -> Unit): KwebFeature {
            val cfg = Configuration()
            configure.invoke(cfg)

            val feature = KwebFeature(cfg)

            return feature
        }

    }

}

fun Routing.setKwebSocket() {
    val feature = application.featureOrNull(KwebFeature)?: throw IllegalStateException("Kweb Feature is not Installed")

    webSocket("/ws") {


        val hello = gson.fromJson<Client2ServerMessage>(((incoming.receive() as Frame.Text).readText()))

        if (hello.hello == null) {
            throw RuntimeException("First message from client isn't 'hello'")
        }

        val remoteClientState = feature.clientState[hello.id]
                ?: throw RuntimeException("Unable to find server state corresponding to client id ${hello.id}")

        assert(remoteClientState.clientConnection is KwebClientConnection.Caching)
        io.kweb.logger.debug("Received message from remoteClient ${remoteClientState.id}, flushing outbound message cache")
        val oldConnection = remoteClientState.clientConnection as KwebClientConnection.Caching
        val webSocketClientConnection = KwebClientConnection.WebSocket(this)
        remoteClientState.clientConnection = webSocketClientConnection
        io.kweb.logger.debug("Set clientConnection for ${remoteClientState.id} to WebSocket")
        oldConnection.read().forEach { webSocketClientConnection.send(it) }


        try {
            for (frame in incoming) {
                try {
                    io.kweb.logger.debug { "Message received from client" }

                    if (frame is Frame.Text) {
                        val message = gson.fromJson<Client2ServerMessage>(frame.readText())
                        if (message.error != null) {
                            feature.handleError(message.error, remoteClientState)
                        } else {
                            when {
                                message.callback != null -> {
                                    val (resultId, result) = message.callback
                                    val resultHandler = remoteClientState.handlers[resultId]
                                            ?: throw RuntimeException("No data handler for $resultId for client ${remoteClientState.id}")
                                    resultHandler(result ?: "")
                                }
                                message.historyStateChange != null -> {

                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    io.kweb.logger.error("Exception while receiving websocket message", e)
                }
            }
        } finally {
            io.kweb.logger.info("WS session disconnected for client id: ${remoteClientState.id}")
            remoteClientState.clientConnection = KwebClientConnection.Caching()
        }
    }
}

fun Route.kweb(path: String = "/{visitedUrl...}", buildPage: WebBrowser.() -> Unit) {
    val feature = application.featureOrNull(KwebFeature)?: throw IllegalStateException("Kweb Feature is not Installed")

    val debug = feature.conf.debug
    val maxPageBuildTimeMS = feature.conf.maxPageBuildTimeMS
    val clientState = feature.clientState

    get(path) {
        val kwebSessionId = createNonce()

        val remoteClientState = clientState.getOrPut(kwebSessionId) {
            RemoteClientState(id = kwebSessionId, clientConnection = KwebClientConnection.Caching())
        }

        val httpRequestInfo = HttpRequestInfo(call.request)

        try {
            if (debug) {
                warnIfBlocking(maxTimeMs = maxPageBuildTimeMS, onBlock = { thread ->
                    logger.warn { "buildPage lambda must return immediately but has taken > $maxPageBuildTimeMS ms.  More info at DEBUG loglevel" }

                    val logStatementBuilder = StringBuilder()
                    logStatementBuilder.appendln("buildPage lambda must return immediately but has taken > $maxPageBuildTimeMS ms, appears to be blocking here:")

                    thread.stackTrace.pruneAndDumpStackTo(logStatementBuilder)
                    val logStatement = logStatementBuilder.toString()
                    logger.debug { logStatement }
                }) {
                    try {
                        buildPage(WebBrowser(kwebSessionId, httpRequestInfo, kweb = feature))
                    } catch (e: Exception) {
                        logger.error("Exception thrown building page", e)
                    }
                    logger.debug { "Outbound message queue size after buildPage is ${(remoteClientState.clientConnection as KwebClientConnection.Caching).queueSize()}" }
                }
            } else {
                try {
                    buildPage(WebBrowser(kwebSessionId, httpRequestInfo, feature))
                } catch (e: Exception) {
                    logger.error("Exception thrown building page", e)
                }
                logger.debug { "Outbound message queue size after buildPage is ${(remoteClientState.clientConnection as KwebClientConnection.Caching).queueSize()}" }
            }
            for (plugin in feature.conf.plugins) {
                feature.execute(kwebSessionId, plugin.executeAfterPageCreation())
            }

            val initialCachedMessages = remoteClientState.clientConnection as KwebClientConnection.Caching

            remoteClientState.clientConnection = KwebClientConnection.Caching()

            val bootstrapHtml = feature.bootstrapHtmlTemplate
                    .replace("--CLIENT-ID-PLACEHOLDER--", kwebSessionId)
                    .replace("<!-- BUILD PAGE PAYLOAD PLACEHOLDER -->", initialCachedMessages.read().map { "handleInboundMessage($it);" }.joinToString(separator = "\n"))

            call.respondText(bootstrapHtml, ContentType.Text.Html)
        } catch (nfe: NotFoundException) {
            call.response.status(HttpStatusCode.NotFound)
            call.respondText("URL ${call.request.uri} not found.", ContentType.parse("text/plain"))
        } catch (e: Exception) {
            val logToken = random.nextLong().toString(16)

            logger.error(e) { "Exception thrown while rendering page, code $logToken" }

            call.response.status(HttpStatusCode.InternalServerError)
            call.respondText("""
                        Internal Server Error.

                        Please include code $logToken in any message report to help us track it down.
""".trimIndent())
        }
    }
}
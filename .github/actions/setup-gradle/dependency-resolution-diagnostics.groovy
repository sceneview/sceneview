import org.gradle.api.Plugin
import org.gradle.api.flow.BuildWorkResult
import org.gradle.api.flow.FlowAction
import org.gradle.api.flow.FlowParameters
import org.gradle.api.flow.FlowProviders
import org.gradle.api.flow.FlowScope
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input

import javax.inject.Inject
import java.nio.charset.StandardCharsets
import java.time.Instant

// Read-only evidence collection: no resolution retries, cache edits, or network on success.
abstract class DependencyResolutionDiagnostics implements FlowAction<DependencyResolutionDiagnostics.Parameters> {
    interface Parameters extends FlowParameters {
        @Input Property<BuildWorkResult> getResult()
        @Input Property<File> getUserHome()
        @Input Property<Boolean> getOffline()
        @Input Property<Boolean> getRefresh()
    }

    static final int MAX_MODULES = 8
    static final int MAX_URLS = 16
    static final long PROBE_DEADLINE_NANOS = 45_000_000_000L

    static String safe(Object value) {
        String.valueOf(value).replaceAll(/[\p{Cntrl}]/, ' ').take(4096)
    }

    static void line(Object value) { System.out.println('  ' + safe(value)) }

    static Set<String> messages(Throwable root) {
        def seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>())
        def pending = [root]
        def result = new LinkedHashSet<String>()
        while (!pending.empty) {
            Throwable failure = pending.remove(pending.size() - 1)
            if (failure == null || !seen.add(failure)) continue
            if (failure.message) result.add(failure.message)
            if (failure.cause) pending.add(failure.cause)
            pending.addAll(failure.suppressed)
            // Gradle aggregates parallel task / dependency failures as multiple causes.
            try {
                def causes = failure.class.getMethod('getCauses').invoke(failure)
                if (causes instanceof Iterable) causes.each { if (it instanceof Throwable) pending.add(it) }
            } catch (Throwable ignored) { }
        }
        result
    }

    static URI repositoryUri(String candidate) {
        try {
            URI uri = new URI(candidate)
            // Never send credentials, follow redirects, or probe arbitrary failure URLs.
            if (uri.scheme != 'https' || uri.rawUserInfo || uri.rawQuery || uri.rawFragment ||
                (uri.port != -1 && uri.port != 443)) return null
            def prefixes = [
                'repo.maven.apache.org': '/maven2/',
                'repo1.maven.org': '/maven2/',
                'dl.google.com': '/dl/android/maven2/',
                'maven.google.com': '/'
            ]
            String prefix = prefixes[uri.host]
            if (!prefix) return null
            if (!uri.path.startsWith(prefix) &&
                !(uri.host == 'dl.google.com' && uri.path.startsWith('/android/maven2/'))) return null
            uri
        } catch (Throwable ignored) { null }
    }

    static void addModule(Map modules, String group, String artifact, String version) {
        // Coordinates are also used as paths; refuse traversal and anything credential-like.
        if (![group, artifact, version].every { it ==~ /[A-Za-z0-9_][A-Za-z0-9_.+~-]*/ && !it.contains('..') }) return
        modules["${group}:${artifact}:${version}".toString()] = [group, artifact, version]
    }

    static Map evidence(Set<String> failures) {
        def modules = new LinkedHashMap<String, List<String>>()
        def urls = new LinkedHashSet<String>()
        failures.findAll { it =~ /(?i)Could not (find|resolve|GET|HEAD)\b/ }.each { message ->
            // Only the coordinates Gradle reports as missing: right after "Could not find/resolve"
            // or inside parentheses. Project paths (":a:b") and "Required by" parents are skipped.
            def coordinates = message =~ /(?:(?i:Could not (?:find|resolve))\s+|\()(?<!:)([A-Za-z0-9_][A-Za-z0-9_.-]*):([A-Za-z0-9_][A-Za-z0-9_.-]*):([A-Za-z0-9_][A-Za-z0-9_.+~-]*)/
            coordinates.each { match -> addModule(modules, match[1], match[2], match[3].replaceAll(/[.,;]+$/, '')) }
            (message =~ /https:\/\/[^\s'"<>\)]+/).each { match ->
                URI uri = repositoryUri(match.toString())
                if (uri != null) {
                    urls.add(uri.toString())
                    String path = uri.path.replaceFirst('^/(dl/)?android/maven2/', '').replaceFirst('^/maven2/', '').replaceFirst('^/', '')
                    def parts = path.split('/')
                    if (parts.length >= 4) addModule(modules, parts[0..-4].join('.'), parts[-3], parts[-2])
                }
            }
        }
        // A blackholed repository makes Gradle report every dependency: probe a bounded sample.
        while (modules.size() > MAX_MODULES) modules.remove(modules.keySet().last())
        modules.values().each { module ->
            String path = "${module[0].replace('.', '/')}/${module[1]}/${module[2]}/"
            ['pom', 'jar'].each { extension ->
                if (!urls.any { it.contains('/' + path) && it.endsWith('.' + extension) }) {
                    ['https://repo.maven.apache.org/maven2/', 'https://dl.google.com/dl/android/maven2/'].each { base ->
                        urls.add("${base}${path}${module[1]}-${module[2]}.${extension}".toString())
                    }
                }
            }
        }
        [modules: modules, urls: urls.take(MAX_URLS) as LinkedHashSet]
    }

    static void fileInfo(File root, File file, String match) {
        line("cache ${root.toPath().relativize(file.toPath())} mtime=${Instant.ofEpochMilli(file.lastModified())} size=${file.length()} bytes ${match}")
    }

    static void cacheInfo(File home, Map<String, List<String>> modules) {
        File root = new File(home, 'caches/modules-2')
        modules.each { id, module ->
            File files = new File(root, "files-2.1/${module[0]}/${module[1]}/${module[2]}")
            line("module ${id}: files-2.1 directory ${files.isDirectory() ? 'present' : 'absent'}")
            if (files.isDirectory()) files.eachFileRecurse { if (it.isFile()) fileInfo(root, it, id) }
        }
        line('Metadata matches name files that mention this exact artifact-version; they are not decoded hit or miss records.')
        def metadata = (root.listFiles() ?: []).findAll { it.isDirectory() && it.name.startsWith('metadata-') }
        if (metadata.empty) line('metadata-* directories absent')
        metadata.each { directory ->
            boolean matched = false
            directory.eachFileRecurse { file ->
                if (!file.isFile() || file.name.endsWith('.lock')) return
                def ids = new LinkedHashSet<String>(modules.findAll { id, module ->
                    file.path.contains("/${module[0]}/${module[1]}/${module[2]}/")
                }.keySet())
                // Indexed keys can serialize group and artifact as separate strings. Scan in
                // bounded chunks, retaining overlap, and report candidate filenames only.
                // Per-version descriptors are matched by path above; scanning them would list every version.
                if (ids.empty && file.name.endsWith('.bin') && !file.path.contains('/descriptors/')) {
                    // Index files store artifact file names; require the exact "artifact-version" string
                    // so cached neighbouring versions of the same module do not match.
                    def needles = modules.collectEntries { id, module -> [(id): "${module[1]}-${module[2]}".toString()] }
                    int overlap = (needles.values().collect { it.length() }.max() ?: 1) as int
                    file.withInputStream { stream ->
                        byte[] buffer = new byte[65536]
                        String tail = ''
                        int count
                        while ((count = stream.read(buffer)) != -1) {
                            String chunk = tail + new String(buffer, 0, count, StandardCharsets.ISO_8859_1)
                            needles.each { id, needle -> if (chunk.contains(needle)) ids.add(id) }
                            tail = chunk.takeRight(overlap)
                        }
                    }
                }
                if (!ids.empty) {
                    matched = true
                    fileInfo(root, file, "module candidates=${ids.join(', ')}")
                }
            }
            if (!matched) line("${directory.name}: no matching module paths or binary strings")
        }
    }

    static void networkProperties() {
        def names = ['http.proxyHost', 'http.proxyPort', 'https.proxyHost', 'https.proxyPort',
                     'http.nonProxyHosts', 'https.nonProxyHosts', 'java.net.useSystemProxies']
        def properties = System.getProperties()
        names.addAll(properties.stringPropertyNames().findAll {
            it.startsWith('org.gradle.internal.http.') || it.startsWith('org.gradle.internal.repository.')
        })
        names.unique().sort().each { name ->
            String value = properties.getProperty(name)
            if (name =~ /(?i)password|credential|auth|token|secret|key/) value = '<redacted>'
            // Also redact user-info and query strings if an allowed property contains a URL.
            value = value?.replaceAll(/(https?:\/\/)[^\s\/@]+@/, '$1<redacted>@')?.replaceAll(/\?\S+/, '?<redacted>')
            line("JVM ${name}=${value == null ? '<unset>' : value}")
        }
    }

    static int request(String url, String method) {
        HttpURLConnection connection
        long started = System.nanoTime()
        try {
            connection = (HttpURLConnection) new URI(url).toURL().openConnection()
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.useCaches = false
            connection.setRequestProperty('Connection', 'close')
            if (method == 'GET') connection.setRequestProperty('Range', 'bytes=0-0')
            int status = connection.responseCode
            int bytes = 0
            if (method == 'GET' && status >= 200 && status < 300) {
                connection.inputStream.withCloseable { bytes = it.read() == -1 ? 0 : 1 }
            }
            def headers = ['Content-Length', 'Age', 'X-Cache', 'Via', 'CF-Ray', 'x-served-by'].collect {
                "${it}=${connection.getHeaderField(it) ?: '<absent>'}"
            }.join(' ')
            line("HTTP ${method} ${url} status=${status} ${headers} elapsedMs=${(System.nanoTime() - started).intdiv(1000000)}" +
                (method == 'GET' ? " Range=bytes=0-0 bytesRead=${bytes}" : ''))
            status
        } catch (Throwable failure) {
            // Exception messages may contain URLs / credentials: print only the type.
            line("HTTP ${method} ${url} error=${failure.class.simpleName} elapsedMs=${(System.nanoTime() - started).intdiv(1000000)}")
            -1
        } finally {
            try { connection?.disconnect() } catch (Throwable ignored) { }
        }
    }

    @Override
    void execute(Parameters parameters) {
        boolean grouped = false
        try {
            def failure = parameters.result.get().failure
            if (!failure.present) return
            def found = evidence(messages(failure.get()))
            if (found.modules.isEmpty() && found.urls.empty) return
            System.out.println('::group::dependency-resolution diagnostics')
            grouped = true
            line("--offline=${parameters.offline.get()} --refresh-dependencies=${parameters.refresh.get()}")
            try { networkProperties() } catch (Throwable ignored) { line('JVM properties unavailable') }
            try { cacheInfo(parameters.userHome.get(), found.modules) }
            catch (Throwable failureInCache) { line("cache inspection unavailable (${failureInCache.class.simpleName})") }
            long deadline = System.nanoTime() + PROBE_DEADLINE_NANOS
            for (String url : found.urls) {
                if (System.nanoTime() > deadline) { line('Probe deadline reached; remaining URLs skipped.'); break }
                if (request(url, 'HEAD') == 200) request(url, 'GET')
            }
        } catch (Throwable ignored) {
            if (grouped) line('Diagnostics incomplete; original build result preserved.')
        } finally {
            if (grouped) {
                try { System.out.println('::endgroup::') } catch (Throwable ignored) { }
            }
        }
    }
}

abstract class DependencyResolutionDiagnosticsPlugin implements Plugin<Settings> {
    @Inject abstract FlowScope getFlowScope()
    @Inject abstract FlowProviders getFlowProviders()

    @Override
    void apply(Settings settings) {
        flowScope.always(DependencyResolutionDiagnostics) { spec ->
            spec.parameters.result.set(flowProviders.buildWorkResult)
            spec.parameters.userHome.set(settings.gradle.gradleUserHomeDir)
            spec.parameters.offline.set(settings.gradle.startParameter.offline)
            spec.parameters.refresh.set(settings.gradle.startParameter.refreshDependencies)
        }
    }
}

// Install before settings evaluation so settings/plugin resolution failures are covered too.
gradle.beforeSettings { settings ->
    try { settings.pluginManager.apply(DependencyResolutionDiagnosticsPlugin) }
    catch (Throwable ignored) { /* Diagnostics must never change the build's result. */ }
}

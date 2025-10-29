import com.intellij.jvm.dfa.analysis.dev.config.TaintConfig
import com.intellij.jvm.dfa.analysis.configurator.taint.rules.TaintRule

TaintConfig {
    method("io.vertx.ext.web.client.WebClientOptions.WebClientOptions") { _ -> { } }
}

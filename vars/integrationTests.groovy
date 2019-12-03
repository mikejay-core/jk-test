library('Utils')
import com.core.Utils


def call(env) {
    echo "Run Integration Tests"
    def utils = new com.core.Utils(this)
    utils.bashScriptReturn "./gradlew integrationTest --info jacocoTestReportIntegration"
    return utils.bashScriptReturn("git log -n 1 --pretty=format:%s ${env.GIT_COMMIT}")
}
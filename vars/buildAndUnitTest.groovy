library('Utils')
import com.core.Utils

def call(env) {
    def utils = new com.core.Utils(this)
    echo "IN BUILD SCRIPT"
    utils.bashScriptReturn "./gradlew clean check jacocoTestReportUnit"
    return utils.bashScriptReturn("git log -n 1 --pretty=format:%s ${env.GIT_COMMIT}")
}
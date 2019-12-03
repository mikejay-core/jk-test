library('Utils')
import com.core.Utils

def call(env) {
    echo "Create Package"
    def utils = new com.core.Utils(this)
    def gitHash = utils.bashScriptReturn("git rev-parse --short ${GIT_COMMIT}").trim()
    utils.bashScript "cd ips-dropwizard && ./package.sh -b ${env.GIT_BRANCH} -c ${gitHash}"
}
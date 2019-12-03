
package com.core

class PipelineUtilities implements Serializable {
    def steps

    PipelineUtilities(steps) {this.steps = steps}

    // ---------- general ------------

    def bashScript(script) {
        steps.sh "${script}"
    }   

    def bashScriptReturn(script) {
        return steps.sh(returnStdout: true, script:"${script}")
    }

    // ------------- -----------------


    def String buildAndUnitTest(env) {
        steps.echo "Build and Unit Test"
        bashScriptReturn("./gradlew clean check jacocoTestReportUnit")
        return bashScriptReturn("git log -n 1 --pretty=format:%s ${env.GIT_COMMIT}")
    }

    def String runIntegrationTests(env) {
        steps.echo "Run Integration Tests"
        bashScriptReturn("./gradlew integrationTest --info jacocoTestReportIntegration")
        return bashScriptReturn("git log -n 1 --pretty=format:%s ${env.GIT_COMMIT}")
    }

    def createPackage(env, service_location) {
        steps.echo "Create Package"
        def service_prefix = service_location.split('-')[0]
        def gitHash = bashScriptReturn("git rev-parse --short ${env.GIT_COMMIT}").trim()

        if(service_prefix == "auth"){
            createLegacyPackage(env, service_location)
            return
        }
        bashScript("cd ${service_location} && ./package.sh -b ${env.GIT_BRANCH} -c ${gitHash}")
    }

    def createLegacyPackage(env, service_prefix) {
        steps.echo "Create Legacy Package"
        def files = findFiles(glob: "${service_location}/config/qa.properties")
        exists = files.length > 0 && files[0].length > 0
        REPLACE_FILE = ""
        if (exists) {
            REPLACE_FILE = "config/qa.properties"
        }
        sh "cd auth-service && ./package.sh -b ${env.GIT_BRANCH} -c ${gitHash} -r ${REPLACE_FILE}"
    }

    def buildDockerImage(env, service_location) {
        steps.echo "Build Docker Image"
        def service_prefix = service_location.split('-')[0]
        def dockerTag = env.GIT_BRANCH + "-" + gitHash
        steps.echo "Docker tag = |${dockerTag}|"
        bashScript('$(aws ecr get-login --no-include-email --region eu-west-1)')
        def escapedBranchName = env.GIT_BRANCH.replaceAll("_", "-")
        def files = findFiles(glob: "${service_location}/build/distributions/nexmo-${service_prefix}_*+" + escapedBranchName + '+' + gitHash + '-1_all.deb')
        def localPath = ""
        if (files.length > 0) {
            //set some variable to indicate the file to load
            localPath = files[0].path
        }
        bashScript("docker build -f Dockerfile --no-cache --network=host --build-arg service_name=${service_prefix} --build-arg local_deb_path=${localPath} -t ${RegistryPrefix}/${repo_name}:${dockerTag} .")
        steps.echo "Push image"
        bashScript("docker images")
        if(bashScriptReturn("aws ecr describe-images --repository-name=${repo_name} --image-ids=imageTag=${dockerTag}") != 0) {
            bashScript("docker push ${RegistryPrefix}/${repo_name}:${dockerTag}")
        } else {
            bashScript("Image Already Exists in ECR")
            env.imageAlreadyExists = "true"
        }
    }

}
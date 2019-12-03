
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

    def createPackage(env) {
        steps.echo "Create Package"
        def gitHash = bashScriptReturn("git rev-parse --short ${GIT_COMMIT}").trim()
        bashScript("cd ips-dropwizard && ./package.sh -b ${env.GIT_BRANCH} -c ${gitHash}")
    }

    def buildDockerImage(env) {
        steps.echo "Build Docker Image"
        def dockerTag = env.GIT_BRANCH + "-" + gitHash
        steps.echo "Docker tag = |${dockerTag}|"
        bashScript('$(aws ecr get-login --no-include-email --region eu-west-1)')
        def escapedBranchName = env.GIT_BRANCH.replaceAll("_", "-")
        def files = findFiles(glob: 'ips-dropwizard/build/distributions/nexmo-ips_*+' + escapedBranchName + '+' + gitHash + '-1_all.deb')
        def localPath = ""
        if (files.length > 0) {
            //set some variable to indicate the file to load
            localPath = files[0].path
        }
        bashScript("docker build -f Dockerfile --no-cache --network=host --build-arg service_name=ips --build-arg local_deb_path=${localPath} -t ${RegistryPrefix}/${repo_name}:${dockerTag} .")
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
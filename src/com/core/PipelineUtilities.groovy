
package com.core

class PipelineUtilities implements Serializable {
    def context
    def npe = [ params: [], name: '']

    String registryPrefix
    String repoName
    String dockerTag = ""
    String puppetBranch = "master"
    boolean imageExists = false

    PipelineUtilities(context, registryPrefix, repoName) {
        this.context = context
        this.registryPrefix = registryPrefix
        this.repoName = repoName
    }

    def String buildAndUnitTest(env) {
        this.context.echo "Build and Unit Test"
        Helper.runScript(this.context, "./gradlew clean check jacocoTestReportUnit")
        return Helper.runScript(this.context, "git log -n 1 --pretty=format:%s ${env.GIT_COMMIT}")
    }

    def String runIntegrationTests(env) {
        this.context.echo "Run Integration Tests"
        Helper.runScript(this.context, "./gradlew integrationTest --info jacocoTestReportIntegration")
        Helper.runScript(this.context, "git log -n 1 --pretty=format:%s ${env.GIT_COMMIT}")
    }

    def createPackage(env, service_location, legacy) {
        this.context.echo "Create Package"
        def gitHash = Helper.runScript(this.context, "git rev-parse --short ${env.GIT_COMMIT}").trim()
        if(legacy){
            createLegacyPackage(env, service_location, gitHash)
            return
        }
        Helper.runScript(this.context, "cd ${service_location} && ./package.sh -b ${env.GIT_BRANCH} -c ${gitHash}")
    }

    def createLegacyPackage(env, service_location, gitHash) {
        this.context.echo "Create Legacy Package"
        def files = this.context.findFiles(glob: "${service_location}/config/qa.properties")
        def exists = files.length > 0 && files[0].length > 0
        def REPLACE_FILE = ""
        if (exists) {
            REPLACE_FILE = "config/qa.properties"
        }
        Helper.runScript(this.context, "cd auth-service && ./package.sh -b ${env.GIT_BRANCH} -c ${gitHash} -r ${REPLACE_FILE}")
        env.gitHash = gitHash
    }

    def buildDockerImage(env, service_location) {
        this.context.echo "Build Docker Image"
        def service_prefix = service_location.split('-')[0]
        def escapedBranchName = env.GIT_BRANCH.replaceAll("_", "-")
        dockerTag = escapedBranchName + "-" + env.gitHash
        this.context.echo "Docker tag = |${dockerTag}|"
        Helper.runScript(this.context, '$(aws ecr get-login --no-include-email --region eu-west-1)')
        def files = this.context.findFiles(glob: "${service_location}/build/distributions/nexmo-${service_prefix}_*+" + escapedBranchName + '+' + env.gitHash + '-1_all.deb')
        def localPath = ""
        if (files.length > 0) {
            //set some variable to indicate the file to load
            localPath = files[0].path
        }
        Helper.runScript(this.context, "docker build -f Dockerfile --no-cache --network=host --build-arg service_name=${service_prefix} --build-arg local_deb_path=${localPath} -t ${registryPrefix}/${repoName}:${dockerTag} .")
        this.context.echo "Push image"
        Helper.runScript(this.context, "docker images")

        try { 
            Helper.runScript(this.context, "aws ecr describe-images --repository-name=${repoName} --image-ids=imageTag=${dockerTag}")
            imageAlreadyExists = "true"
        } catch (Exception inf) {
            Helper.runScript(this.context, "docker push ${registryPrefix}/${repoName}:${dockerTag}")
        }
    }

    def retrieveConfiguration(env, service_location) {
        def npePreset = service_location.split('-')[0]
        npe.params = this.context.readJSON text: Helper.runScript(this.context, "curl -ks \"https://api.app.npe/presets/${npePreset}/default-params\"").trim()
        npe.params.param["env.puppet_branch"] = puppetBranch
        npe.params.param["metaconf.docker_tag"] = dockerTag
        npe.params.param["metaconf.auth_branch"] = env.GIT_BRANCH
        npe.params.param["env.core_config_db_db_url"] = "jdbc:mysql://mysql-db/config"
    }

    def buildNPE(env, npe_key, npe_user) {
        String something = Helper.runScript(this.context, "curl -ks -H \"Content-Type: application/json\" -d '${groovy.json.JsonOutput.toJson(npe.params)}' -X POST \"https://${npe_user}:${npe_key}@api.app.npe/envs\"").trim()
        Object response = this.context.readJSON text: something
        npe.name = response.data[0].name
        this.context.echo "NPE Name: ${npe.name}"
    }

    def waitForNPEEnv(env, npe_key, npe_user) {
        this.context.echo "WAITING FOR NPE"
        int attempts = 0
        while(attempts < 40){
            Object response = this.context.readJSON text: Helper.runScript(this.context, "curl -ks \"https://${npe_user}:${npe_key}@api.app.npe/envs/${npe.name}/status\"").trim()
            if (response.data[0].available != false) { 
                return true
            } else {
                sleep 30000
            }
            this.context.echo "ATTEMPTS ${attempts} : Available ${response.data[0].available}"
            attempts++
        }
    }

    def runQATests(env, qa_test_set, npe_short_name) {
        String target_branch = "master" //Helper.getQATestsBranch(env, this.context, qa_test_set, username, password) //TODO
        this.context.echo "Checkout QA Tests"
        this.context.checkout([$class: 'GitSCM', branches: [[name: "${target_branch}"]], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'cfb2df52-09d4-4f27-ad17-71a58c4995d9', url: 'https://github.com/nexmoinc/qatests']]])
        Helper.runScript(this.context, getQAShellScript(qa_test_set, npe_short_name))
    }


    def getQAShellScript(qa_test_set){
        return """\
                    set -e 
                    echo "Create python virtual env"
                    pipenv install --ignore-pipfile
                    pipenv install allure-pytest pytest-rerunfailures --skip-lock
                    echo "Run tests"
                    export QA_TEST_ENVIRONMENT=npe:core:${npe.name}:auth1 && export PYTHONPATH=\$PYTHONPATH:\$(pwd)
                    pipenv run python -m pytest testcases/core_projects/auth -v -m "trusted and not skip and ${qa_test_set}" --junitxml=${this.context.WORKSPACE}/pytestresults.xml --alluredir=${this.context.WORKSPACE}/allure-results --reruns=1}
                    echo "Delete virtual env"
                    pipenv --rm
                """
    }

    def dropNPE(env, npe_key, npe_user) {
        this.context.echo "Deleting environment ${npe.name}"
        def resp = Helper.runScript(this.context, "curl -ks -X DELETE \"https://${npe_user}:${npe_key}@api.app.npe/envs/${npe.name}\"")
        this.context.echo "${resp}"
    }

}
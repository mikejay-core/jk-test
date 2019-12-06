package com.core


class Helper implements Serializable {
    def context
    
    Helper(context) {this.context = context}

    static def runScript(context, script) {
        context.echo "running script ${script}"
        def result = context.sh(returnStdout: true, script:"${script}")
        context.echo "result: ${result}"
        return result
    }

    static def getQAShellScript(context, qaTestSet, npeName){
        context.echo "about to run test"
        return """\
                    set -e && 
                    echo "Create python virtual env" &&
                    pipenv install --ignore-pipfile && 
                    pipenv install allure-pytest pytest-rerunfailures --skip-lock &&
                    echo "Run tests" &&
                    export QA_TEST_ENVIRONMENT=npe:core:${npeName}:core1 && export PYTHONPATH=\$PYTHONPATH:\$(pwd) &&
                    pipenv run python -m pytest testcases/core_projects/auth -v -m "trusted and not skip and ${qaTestSet}" --junitxml=${context.WORKSPACE}/pytestresults.xml --alluredir=${context.WORKSPACE}/allure-results --reruns=1 &&
                    echo "Delete virtual env" &&
                    pipenv --rm
                """
    }

    static def pushLatestTagToECR(env, context, imageExists, dockerTag, registryPrefix, repoName) {
        if (env.GIT_BRANCH == "master" && !imageExists) {
            logInToECR(context)
            def dockerId = runScript(context, "docker images | grep ${dockerTag} | awk {'print \$3'}").trim()
            runScript("docker tag ${dockerId} ${registryPrefix}/${repoName}:latest")
            runScript("docker push ${registryPrefix}/${repoName}:latest")
        }
    }

    static def logInToECR(context) {
        runScript(context,'$(aws ecr get-login --no-include-email --region eu-west-1)')
    }

    static def deleteImageFromECF(context) {
        logInToECR(context)
        runScript(context, "aws ecr batch-delete-image --repository-name ${repo_name} --image-ids imageTag=${dockerTag}")
    }

    def String getQATestsBranch(env, testBranch, username, password) {
        this.context.echo "IN GET QA TESTS BRANCH"
        // helper function to find corresponding qatests branch to be used for testing dev branch
        def result = ""
        if (env.GIT_BRANCH.toLowerCase() == "dev" || env.GIT_BRANCH == "master") {
            result = "master" // if we are on dev branch, always run tests from qatests master
        } else {
            if (testBranch == "" || testBranch == null) {
                def (branchPrefix, filter1, filter2, qaTestsTargetBranch) = ["", "", "", ""]
                if (env.GIT_BRANCH =~ /^\w+(-|_)\d+/) {
                    this.context.echo "dev branch has matched jira ticket name convention"
                    branchPrefix = (env.GIT_BRANCH =~ /^\w+(-|_)\d+/)[0][0] // we have to use same regexp twice because we can't use match object in declarative pipelines because of serialization
                    filter1 = branchPrefix
                    filter2 = branchPrefix.contains('-') ? branchPrefix.replace('-', '_') : branchPrefix.replace('_', '-') // test branch can be CORE-xxxx or CORE_xxxx
                } else {
                    this.context.echo "dev branch has not matched jira ticket name convention"
                    branchPrefix = env.GIT_BRANCH
                    (filter1, filter2) = [branchPrefix, branchPrefix]
                }
                def branchQAHash = runScript("git ls-remote https://${username}:${password}@github.com/nexmoinc/qatests.git | grep \"$filter1\\|$filter2\" || echo 'switch to qatests master'").toString().trim()
                if (branchQAHash == "switch to qatests master") {
                    this.context.echo "No corresponding qatests branch found -> using master"
                    qaTestsTargetBranch = "master"
                } else {
                    this.context.echo "found qatests branch ${branchQAHash}"
                    qaTestsTargetBranch = branchQAHash.split()[1] // first element is hashcommit, second is branch name we need
                }
                result = qaTestsTargetBranch
            } else {
                this.context.echo "use provided parameter"
                result = testBranch // use manually provided qatests branch
            }
        }
        this.context.echo "using branch ${result}"
        return result
    }

    def runScript(script) {
        this.context.echo "running script ${script}"
        def result = this.context.sh(returnStdout: true, script:"${script}")
        this.context.echo "result: ${result}"
        return result
    }

    def push_latest_tag(env, repositoryPrefix, repoName, dockerTag) {
        if (env.GIT_BRANCH == "master" && imageExists) {
            this.context.echo("Pushing latest tag")
            runScript('$(aws ecr get-login --no-include-email --region eu-west-1)')
            def dockerId = runScript("docker images | grep ${dockerTag} | awk {'print \$3'}").trim()
            runScript( "docker tag ${dockerId} ${registryPrefix}/${repoName}:latest")
            runScript("docker push ${registryPrefix}/${repoName}:latest")
        }
    }

    def publishUnitTest(env, reportDir) {
        this.context.publishHTML([allowMissing: true,
                    alwaysLinkToLastBuild: true,
                    keepAll: true,
                    reportDir: reportDir,
                    reportFiles: 'index.html',
                    reportName: 'Unit Test Coverage',
                    reportTitles: 'Unit Test Coverage'])
    }

    def publishIntegrationTest(env, reportDir) {
        this.context.publishHTML([allowMissing: true,
                    alwaysLinkToLastBuild: true,
                    keepAll: true,
                    reportDir: reportDir,
                    reportFiles: 'index.html',
                    reportName: 'Integration Test Coverage',
                    reportTitles: 'Integration Test Coverage'])
    }

    def publishQATestResults(env) {
        String cmd = """\
                        set +e 
                        [ -d \"allure-report/history\" ] && cp -r allure-report/history allure-results 
                        set -e
                    """
        runScript(cmd)
        this.context.allure([
                                includeProperties: false,
                                jdk: '',
                                properties: [],
                                reportBuildPolicy: 'ALWAYS',
                                results: [[path: 'allure-results']]
                        ])
    }

    def slackSend(env, _channel, _color, _status) {
        this.context.slackSend channel: "${_channel}", color: "${_color}", message: "Build ${_status} - job: ${env.JOB_NAME} build number: ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"
    }

}
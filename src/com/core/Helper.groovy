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

    static def getQAShellScript(context, qa_test_set, npe_name, npe_short_name){
        return """\
                    set -e && 
                    echo "Create python virtual env" &&
                    pipenv install --ignore-pipfile && 
                    pipenv install allure-pytest pytest-rerunfailures --skip-lock &&
                    echo "Run tests" &&
                    export QA_TEST_ENVIRONMENT=npe:core:${npe_name}:${npe_short_name} && export PYTHONPATH=\$PYTHONPATH:\$(pwd) &&
                    pipenv run python -m pytest testcases/core_projects/auth -v -m "trusted and not skip and ${qa_test_set}" --junitxml=${context.WORKSPACE}/pytestresults.xml --alluredir=${context.WORKSPACE}/allure-results --reruns=1 &&
                    echo "Delete virtual env" &&
                    pipenv --rm
                """
    }

    static def String getQATestsBranch(env, context, qa_tests_branch, username, password) {
        node('master') {
            // helper function to find corresponding qatests branch to be used for testing dev branch
            def result = ""
            context.withCredentials([usernamePassword(credentialsId: 'cfb2df52-09d4-4f27-ad17-71a58c4995d9', passwordVariable: 'password', usernameVariable: 'username')]) { //TODO
                script {
                    if (env.GIT_BRANCH.toLowerCase() == "dev" || env.GIT_BRANCH == "master") {
                        result = "master" // if we are on dev branch, always run tests from qatests master
                    } else {
                        if (qa_tests_branch == "") {
                            def (branch_prefix, filter1, filter2, qatests_target_branch) = ["", "", "", ""]
                            if (env.GIT_BRANCH =~ /^\w+(-|_)\d+/) {
                                context.echo "dev branch has matched jira ticket name convention"
                                branch_prefix = (env.GIT_BRANCH =~ /^\w+(-|_)\d+/)[0][0] // we have to use same regexp twice because we can't use match object in declarative pipelines because of serialization
                                filter1 = branch_prefix
                                filter2 = branch_prefix.contains('-') ? branch_prefix.replace('-', '_') : branch_prefix.replace('_', '-') // test branch can be CORE-xxxx or CORE_xxxx
                            } else {
                                context.echo "dev branch has not matched jira ticket name convention"
                                branch_prefix = env.GIT_BRANCH
                                (filter1, filter2) = [branch_prefix, branch_prefix]
                            }
                            def branch_qa_hash = runScript(context, "git ls-remote https://${username}:${password}@github.com/nexmoinc/qatests.git | grep \"$filter1\\|$filter2\" || echo 'switch to qatests master'").toString().trim()
                            if (branch_qa_hash == "switch to qatests master") {
                                context.echo "No corresponding qatests branch found -> using master"
                                qatests_target_branch = "master"
                            } else {
                                context.echo "found qatests branch ${branch_qa_hash}"
                                qatests_target_branch = branch_qa_hash.split()[1] // first element is hashcommit, second is branch name we need
                            }
                            result = qatests_target_branch
                        } else {
                            context.echo "use provided parameter"
                            result = qa_tests_branch // use manually provided qatests branch
                        }
                    }
                }
            }
            return result
        }
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

    def publishUnitTest(env, report_dir) {
        this.context.publishHTML([allowMissing: true,
                    alwaysLinkToLastBuild: true,
                    keepAll: true,
                    reportDir: report_dir,
                    reportFiles: 'index.html',
                    reportName: 'Unit Test Coverage',
                    reportTitles: 'Unit Test Coverage'])
    }

    def publishIntegrationTest(env, report_dir) {
        this.context.publishHTML([allowMissing: true,
                    alwaysLinkToLastBuild: true,
                    keepAll: true,
                    reportDir: report_dir,
                    reportFiles: 'index.html',
                    reportName: 'Integration Test Coverage',
                    reportTitles: 'Integration Test Coverage'])
    }

    def publishQATestResults(env) {
        String cmd = """\
                        set +e &&
                        cp -r allure-report/history allure-results &&
                        set -e &&
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
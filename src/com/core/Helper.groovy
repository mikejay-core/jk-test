package com.core


class Helper implements Serializable {
    def context
    
    Helper(context) {this.context = context}

    Static def runScript(context, script) {
        return context.sh(returnStdout: true, script:"${script}")
    }

    Static def String getQATestsBranch(env, context, qa_tests_branch) {
        node('master') {
            // helper function to find corresponding qatests branch to be used for testing dev branch
            def result = ""
            context.withCredentials([usernamePassword(credentialsId: 'cfb2df52-09d4-4f27-ad17-71a58c4995d9', passwordVariable: 'password', usernameVariable: 'username')]) {
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
        return this.context.sh(returnStdout: true, script:"${script}")
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

    def slackSend(env, channel, color, status) {
        this.context.slackSend channel: "${channel}", color: "${color}", message: "Build ${status} - job: ${env.JOB_NAME} build number: ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"
    }

}
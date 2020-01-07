package com.github.helpermethod

import com.jcraft.jsch.Session
import org.apache.maven.artifact.Artifact
import org.apache.maven.artifact.factory.ArtifactFactory
import org.apache.maven.artifact.metadata.ArtifactMetadataSource
import org.apache.maven.artifact.repository.ArtifactRepository
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import org.apache.maven.settings.Settings
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand.ListMode.ALL
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RepositoryBuilder
import org.eclipse.jgit.transport.*
import org.eclipse.jgit.util.FS
import org.jdom2.Document
import org.jdom2.filter.Filters.element
import org.jdom2.input.SAXBuilder
import org.jdom2.input.sax.SAXHandler
import org.jdom2.input.sax.SAXHandlerFactory
import org.jdom2.output.XMLOutputter
import org.jdom2.xpath.XPathFactory
import org.xml.sax.Attributes

@Mojo(name = "update")
class UpdateMojo: AbstractMojo() {
    @Parameter(defaultValue = "\${project}", readonly = true)
    lateinit var mavenProject: MavenProject
    @Parameter(defaultValue = "\${localRepository", readonly = true)
    lateinit var localRepository: ArtifactRepository
    @Parameter(defaultValue = "\${settings}", readonly = true)
    lateinit var settings: Settings
    @Parameter(property = "connectionUrl", defaultValue = "\${project.scm.connection}")
    lateinit var connectionUrl: String
    @Parameter(property = "developerConnectionUrl", defaultValue = "\${project.scm.developerConnection}")
    lateinit var developerConnectionUrl: String
    @Parameter(property = "connectionType", defaultValue = "connection")
    lateinit var connectionType: String

    private val connection: String
        get() = if (connectionType == "developerConnection") developerConnectionUrl else connectionUrl

    @Component
    lateinit var artifactFactory: ArtifactFactory
    @Component
    lateinit var artifactMetadataSource: ArtifactMetadataSource

    override fun execute() {
        withGit { git, head ->
            listOf(::parentUpdate)
                .flatMap { it() }
                .map { it to "dependency-update/${it.groupId}-${it.artifactId}-${it.latestVersion}" }
                .filter { (_, branchName) -> git.branchList().setListMode(ALL).call().none { it.name == "refs/remotes/origin/$branchName" }}
                .forEach { (update, branchName) ->
                    git
                        .checkout()
                        .setStartPoint(head)
                        .setCreateBranch(true)
                        .setName(branchName)
                        .call()

                    withPom(update.modification)

                    git
                        .add()
                        .addFilepattern("pom.xml")
                        .call()

                    git
                        .commit()
                        .setAuthor(PersonIdent("dependency-update-bot", ""))
                        .setMessage("Bump $update.artifactId from $update.currentVersion to $update.latestVersion")
                        .call()

                    git
                        .push()
                        .setRefSpecs(RefSpec("$branchName:$branchName"))
                        .apply {
                            val uri = URIish(connection.removePrefix("scm:git:"))

                            when (uri.scheme) {
                                "https" -> {
                                    val (username, password) = usernamePassword(uri)

                                    setCredentialsProvider(UsernamePasswordCredentialsProvider(username, password))
                                }
                                else -> {
                                    setTransportConfigCallback(TransportConfigCallback {
                                        if (it !is SshTransport) return@TransportConfigCallback

                                        it.sshSessionFactory = object : JschConfigSessionFactory() {
                                            override fun configure(hc: OpenSshConfig.Host?, session: Session?) {
                                            }

                                            override fun createDefaultJSch(fs: FS?) =
                                                settings.getServer(uri.host).run {
                                                    super.createDefaultJSch(fs).apply { addIdentity(privateKey, passphrase) }
                                                }
                                            }
                                        })
                                }
                            }
                        }
                        .setPushOptions(listOf("merge_request.create"))
                        .call()
                }
        }
    }

    private fun usernamePassword(uri: URIish) =
            if (uri.user != null) uri.user to uri.pass else settings.getServer(uri.host).run { username to password }

    private fun withGit(f: (Git, String) -> Unit) {
        val git = Git(
            RepositoryBuilder()
                .readEnvironment()
                .findGitDir(mavenProject.basedir)
                .setMustExist(true)
                .build()
        )

        f(git, git.repository.fullBranch)

        git.close()
    }

    private fun withPom(f: (Document) -> Unit) {
        val namespaceIgnoringSaxBuilder = SAXBuilder().apply {
            saxHandlerFactory = SAXHandlerFactory {
                object : SAXHandler() {
                    override fun startElement(namespaceURI: String?, localName: String?, qName: String?, atts: Attributes?) {
                        super.startElement("", localName, qName, atts)
                    }

                    override fun startPrefixMapping(prefix: String?, uri: String?) {
                    }
                }
            }
        }

        val pom = namespaceIgnoringSaxBuilder.build(mavenProject.file)

        f(pom)

        XMLOutputter().output(pom, mavenProject.file.writer())
    }

    fun parentUpdate() =
        listOfNotNull(mavenProject.parentArtifact)
            .mapNotNull {
                update(it) { version, pom ->
                    XPathFactory.instance().compile("/project/parent/version", element()).evaluateFirst(pom)?.text = version
                }
            }

    private fun update(artifact: Artifact, f: (String, Document) -> Unit): Update? {
        val latestVersion = retrieveLatestVersion(artifact)

        if (artifact.version == latestVersion) return null

        return Update(groupId = artifact.groupId, artifactId = artifact.artifactId, version = artifact.version, latestVersion = latestVersion, modification = { pom: Document -> f(latestVersion, pom) })
    }

    private fun retrieveLatestVersion(artifact: Artifact) =
        artifactMetadataSource
            .retrieveAvailableVersions(artifact, localRepository, mavenProject.remoteArtifactRepositories)
            .filter { it.qualifier != "SNAPSHOT" }
            .max()
            .toString()

    data class Update(val groupId: String, val artifactId: String, val version: String, val latestVersion: String, val modification: (Document) -> Unit)
}
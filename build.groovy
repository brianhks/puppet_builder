import com.github.jknack.semver.AndExpression
import com.github.jknack.semver.RelationalOp
import com.github.jknack.semver.Semver
import groovy.json.JsonSlurper
import org.rauschig.jarchivelib.Archiver
import org.rauschig.jarchivelib.ArchiverFactory
import tablesaw.*
import tablesaw.addons.ivy.ResolveRule
import tablesaw.addons.ivy.RetrieveRule
import tablesaw.rules.*
import tablesaw.addons.*
import tablesaw.addons.ivy.PomRule
import tablesaw.addons.ivy.PublishRule

saw = Tablesaw.getCurrentTablesaw()
saw.includeDefinitionFile("definitions.xml")

buildDir = ".puppet_builder"
libDir = "modules"

//The following are used when packaging the modules together
projectName = saw.getProperty("project_name")
projectPackage = saw.getProperty("project_package")
projectVersion = saw.getProperty("project_version")


ivySettings = [new File(saw.getTablesawPath()+"/ivysettings.xml")]
buildDirRule = new DirectoryRule(buildDir)
libDirRule = new DirectoryRule(libDir)

def splitName(String name)
{
	def packageName, moduleName

	if (name.contains("/"))
		(packageName, moduleName) = name.tokenize('/')
	else
		(packageName, moduleName) = name.tokenize('-')

	[packageName, moduleName]
}

//Read metadata for module
def jsonSlurper = new JsonSlurper()
def metadataFile = "metadata.json"
def metadata = jsonSlurper.parse(new File(metadataFile))
def (packageName, moduleName) = splitName(metadata.name)

def ivyFileName = buildDir+"/ivy.xml"
def ivyFile = new File(ivyFileName)

def ivyRule = new SimpleRule()
		.addDepend(buildDirRule)
		.addTarget(ivyFile.getAbsolutePath())
		.addSource(metadataFile)
		.setProperty("metadata", metadata)
		.setProperty("packageName", packageName)
		.setProperty("moduleName", moduleName)
		.setMakeAction("doIvyRule")

//Setup ivy for each module
def resolveRule = new ResolveRule(ivyFile, ivySettings, Collections.singleton("*"))
//resolveRule.setName("ivy-resolve-${it}")
resolveRule.setName(null)

def retrieveRule = new RetrieveRule(ivyFile)
		.addDepend(libDirRule)
		.setMakeAction("doModuleRetrieve")
		.setName("retrieve")
		.setRetrievePattern("${libDir}/[organization]-[artifact]-[revision].[ext]")

retrieveRule.setResolveRule(resolveRule)


//Setup bundle rule for module
tarRule = new TarRule("${buildDir}/${moduleName}-${metadata.version}.tar")
		.addFileSet(new RegExFileSet(".", ".*").addExcludeDir(buildDir).recurse())
		.addDepend(buildDirRule)

gzipRule = new GZipRule("bundle").setSource(tarRule.getTarget())
		.setDescription("Create bundled module.")
		.setTarget("${buildDir}/${moduleName}-${metadata.version}.tar.gz")
		.addDepend(tarRule)

//setup publish for module
if (metadata.version.contains("SNAPSHOT"))
	defaultResolver = "local-ivy-snapshot"
else
	defaultResolver = "local-ivy"

def publishRule = new PublishRule(ivyFile, defaultResolver, resolveRule)
		.setName("publish")
		.setDescription("Publish pom and jar to maven snapshot repo")
		.setArtifactId(moduleName)
		.setGroupId(packageName)
		.setVersion(metadata.version)
		.addDepend(buildDirRule)
		.publishMavenMetadata("maven-metadata.xml")
		.setOverwrite(true)

publishRule.addArtifact(ivyRule.getTarget())
		.setType("ivy")
		.setExt("xml")
publishRule.addArtifact(gzipRule.getTarget())
		.setType("module")
		.setExt("tar.gz")


def doModuleRetrieve(Rule rule)
{
	rule.doMakeAction(rule)

	def zipFiles = new RegExFileSet(libDir, ".*\\.tar\\.gz").getFullFilePaths()

	zipFiles.each()
			{
				File archive = new File(it)
				File destination = new File(libDir)

				Archiver archiver = ArchiverFactory.createArchiver("tar", "gz")
				archiver.extract(archive, destination)
				
				saw.delete(it)
			}
			
	//Rename the folders
	folders = new File(libDir).listFiles()
	folders.each()
	{
		it.renameTo(new File("modules", it.getName().split("-")[1]));
	}
}

public class RangeVersion
{
	public String leftSymbol = "("
	public String leftVersion = ""
	public String rightVersion = ""
	public String rightSymbol = ")"

	public String toString()
	{
		return (leftSymbol+leftVersion+","+rightVersion+rightSymbol)
	}
}


def translateRelationalOp(RelationalOp op, RangeVersion rangeVersion)
{
	if (op == null)
		return
	if (op instanceof RelationalOp.GreaterThan)
	{
		rangeVersion.leftSymbol = "]";
		rangeVersion.leftVersion = op.getSemver().toString()
	}
	else if (op instanceof RelationalOp.GreatherThanEqualsTo)
	{
		rangeVersion.leftSymbol = "[";
		rangeVersion.leftVersion = op.getSemver().toString()
	}
	else if (op instanceof RelationalOp.LessThan)
	{
		rangeVersion.rightSymbol = "[";
		rangeVersion.rightVersion = op.getSemver().toString()
	}
	else if (op instanceof RelationalOp.LessThanEqualsTo)
	{
		rangeVersion.rightSymbol = "]";
		rangeVersion.rightVersion = op.getSemver().toString()
	}
}


def translateVersion(String version)
{
	def semver = Semver.create(version)

	if (semver instanceof com.github.jknack.semver.Version)
	{
		return "${semver}"
	}
	else if (semver instanceof RelationalOp)
	{
		def rangeVersion = new RangeVersion()
		translateRelationalOp(semver, rangeVersion)
		return rangeVersion.toString()
	}
	else if (semver instanceof AndExpression)
	{
		def rangeVersion = new RangeVersion()
		translateRelationalOp(semver.getLeft(), rangeVersion)
		translateRelationalOp(semver.getRight(), rangeVersion)
		return rangeVersion.toString()
	}

	""
}

void doIvyRule(Rule rule)
{
	def metadata = rule.getProperty("metadata")
	def packageName = rule.getProperty("packageName")
	def moduleName = rule.getProperty("moduleName")

	def dependencies = ''

	metadata.dependencies.each()
			{
				def (depPackageName, depModuleName) = splitName(it.name)

				def version = translateVersion(it.version_requirement)

				dependencies += """
		<dependency org="${depPackageName}" name="${depModuleName}" rev="${version}">
			<artifact name="${depModuleName}" type="module" ext="tar.gz"/>
		</dependency>"""
			}

	//Todo add pulication attribute to info
	def ivyXml = """<ivy-module version="2.0">
	<info organisation="${packageName}" module="${moduleName}" revision="${metadata.version}"/>
	<configurations defaultconf="default" >
		<conf name="default"/>
	</configurations>
	<dependencies>${dependencies}
	</dependencies>
</ivy-module>
"""
	def ivyFile = new File(rule.getTargets().iterator().next())
	ivyFile.write ivyXml
}


//==============================================================================
//Rule to package all modules and dependencies.
/*packageRule = new ZipRule("package", "${buildDir}/${projectName}.zip")
		.setDescription("Package all modules and dependencies into one artifact")
		.addDepend("retrieve-all")
		.addFileSet(new RegExFileSet(modulesDir, ".*").recurse())
		.addFileSet(new RegExFileSet(libDir, ".*").recurse())*/


//==============================================================================
//Rule for running lint on all pp files
ppFiles = new RegExFileSet("manifests", ".*\\.pp").recurse()
		.getFullFilePaths()

lintRule = new SimpleRule("puppet-lint").setDescription("Run puppet-lint on all puppet files")
		.addSources(ppFiles)
		.setMakeAction("runLint")
		
void runLint(Rule rule)
{
	lintDef = saw.getDefinition("puppet-lint")
	//lintDef.set("fail-on-warnings")
	
	for (String source : rule.getSources())
	{
		lintDef.set("manifest", source)
		saw.exec(lintDef.getCommand())
	}
}




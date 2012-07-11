1) tomcat/shared/classes/alfresco/extension/custom-log4j.properties

#Folder Quota
log4j.logger.org.alfresco.extension.folderquota=DEBUG

2) tomcat/shared/classes/alfresco/web-extension/share-config-custom.xml

	<config evaluator="string-compare" condition="DocumentLibrary">
		<aspects>
			<visible>
				<aspect name="fq:quota"/>
			</visible>
			<addable></addable>
			<removeable></removeable>
		</aspects>
	</config>
	<config evaluator="node-type" condition="cm:folder">
		<forms>
			<form>
				<field-visibility>
					<show id="fq:sizeQuota"/>
					<show id="fq:sizeCurrent" for-mode="view"/>
				</field-visibility>
			</form>
			<form id="doclib-simple-metadata">
				<field-visibility>
					<show id="fq:sizeQuota"/>
					<show id="fq:sizeCurrent" for-mode="view"/>
				</field-visibility>
				<edit-form template="../documentlibrary/forms/doclib-simple-metadata.ftl"/>
			</form>
			<form id="doclib-inline-edit">
				<field-visibility>
					<show id="fq:sizeQuota"/>
					<show id="fq:sizeCurrent" for-mode="view"/>
				</field-visibility>
			</form>
		</forms>
	</config>


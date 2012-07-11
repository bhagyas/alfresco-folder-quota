package org.alfresco.extension.folderquota;

import org.alfresco.service.cmr.repository.NodeRef;

/**
 * Tiny class that encapsulates the size change (quota folder ref and size delta)
 */
public class SizeChange {
	public NodeRef nodeRef;
	public long sizeChange;
	public SizeChange(NodeRef nodeRef,long sizeChange) {
		this.nodeRef = nodeRef;
		this.sizeChange = sizeChange;
	}
}

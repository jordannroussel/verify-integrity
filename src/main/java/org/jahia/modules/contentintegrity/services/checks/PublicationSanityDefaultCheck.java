package org.jahia.modules.contentintegrity.services.checks;

import org.apache.commons.lang.StringUtils;
import org.jahia.api.Constants;
import org.jahia.modules.contentintegrity.api.ContentIntegrityCheck;
import org.jahia.modules.contentintegrity.services.ContentIntegrityError;
import org.jahia.modules.contentintegrity.services.ContentIntegrityErrorList;
import org.jahia.modules.contentintegrity.services.impl.AbstractContentIntegrityCheck;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ItemNotFoundException;
import javax.jcr.RepositoryException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import static org.jahia.api.Constants.JAHIAMIX_LASTPUBLISHED;
import static org.jahia.api.Constants.JCR_LASTMODIFIED;
import static org.jahia.api.Constants.LASTPUBLISHED;
import static org.jahia.api.Constants.LIVE_WORKSPACE;
import static org.jahia.api.Constants.PUBLISHED;

@Component(service = ContentIntegrityCheck.class, immediate = true, property = {
        ContentIntegrityCheck.ExecutionCondition.APPLY_ON_NT + "=" + Constants.JAHIAMIX_LASTPUBLISHED,
        ContentIntegrityCheck.ExecutionCondition.APPLY_ON_WS + "=" + Constants.EDIT_WORKSPACE
})
public class PublicationSanityDefaultCheck extends AbstractContentIntegrityCheck implements AbstractContentIntegrityCheck.SupportsIntegrityErrorFix {

    private static final Logger logger = LoggerFactory.getLogger(PublicationSanityDefaultCheck.class);
    private static final String DIFFERENT_PATH_ROOT = "different-path-root";

    private enum ErrorType {NO_LIVE_NODE, DIFFERENT_PATH}
    private Map<String, Object> inheritedErrors = new HashMap<>();

    @Override
    public void finalizeIntegrityTestInternal() {
        inheritedErrors.clear();
    }

    @Override
    public ContentIntegrityErrorList checkIntegrityBeforeChildren(JCRNodeWrapper node) {
        try {
            final JCRSessionWrapper liveSession = JCRSessionFactory.getInstance().getCurrentSystemSession(LIVE_WORKSPACE, null, null);
            if (node.hasProperty(PUBLISHED) && node.getProperty(PUBLISHED).getBoolean()) {
                final JCRNodeWrapper liveNode;
                try {
                    liveNode = liveSession.getNodeByIdentifier(node.getIdentifier());
                } catch (ItemNotFoundException infe) {
                    final String msg = "Found a node flagged as published, but no corresponding live node exists";
                    final ContentIntegrityError error = createError(node, msg);
                    error.setExtraInfos(ErrorType.NO_LIVE_NODE);
                    return createSingleError(error);
                }

                /*
                 comparison of the path between the default and live workspaces: if a node has a different path, then the test will not be done
                 on its subtree
                 */
                final String nodePath = node.getPath();
                if (!inheritedErrors.containsKey(DIFFERENT_PATH_ROOT) && !StringUtils.equals(nodePath, liveNode.getPath())) {
                    inheritedErrors.put(DIFFERENT_PATH_ROOT, nodePath);
                    if (!hasPendingModifications(node)) {
                        final String msg = "Found a published node, with no pending modifications, but the path in live is different";
                        final ContentIntegrityError error = createError(node, msg);
                        error.setExtraInfos(ErrorType.DIFFERENT_PATH);
                        return createSingleError(error);
                    }
                }
            }
            return null;
        } catch (RepositoryException e) {
            logger.error("", e);
            return null;
        }
    }

    @Override
    public ContentIntegrityErrorList checkIntegrityAfterChildren(JCRNodeWrapper node) {
        if (inheritedErrors.containsKey(DIFFERENT_PATH_ROOT) && StringUtils.equals(node.getPath(), (String) inheritedErrors.get(DIFFERENT_PATH_ROOT)))
            inheritedErrors.remove(DIFFERENT_PATH_ROOT);
        return super.checkIntegrityAfterChildren(node);
    }

    private boolean hasPendingModifications(JCRNodeWrapper node) {
        try {
            if (!node.isNodeType(JAHIAMIX_LASTPUBLISHED)) return false;
            if (!node.hasProperty(LASTPUBLISHED)) return true;
            final Calendar lastPublished = node.getProperty(LASTPUBLISHED).getDate();
            if (lastPublished == null) return true;
            if (!node.hasProperty(JCR_LASTMODIFIED)) {
                // If this occurs, then it should be detected by another integrityCheck. But here there's no way to deal with such node.
                logger.error("The node has no last modification date set " + node.getPath());
                return false;
            }
            final Calendar lastModified = node.getProperty(JCR_LASTMODIFIED).getDate();

            return lastModified.after(lastPublished);
        } catch (RepositoryException e) {
            logger.error("", e);
            // If we can't validate that there's some pending modifications here, then we assume that there are no one.
            return false;
        }
    }

    @Override
    public boolean fixError(JCRNodeWrapper node, ContentIntegrityError integrityError) throws RepositoryException {
        final Object errorExtraInfos = integrityError.getExtraInfos();
        if (!(errorExtraInfos instanceof ErrorType)) {
            logger.error("Unexpected error type: " + errorExtraInfos);
            return false;
        }
        final ErrorType errorType = (ErrorType) errorExtraInfos;
        switch (errorType) {
            case NO_LIVE_NODE:
                node.getProperty(PUBLISHED).remove();
                node.getSession().save();
                return true;
        }
        return false;
    }
}

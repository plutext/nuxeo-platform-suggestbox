/*
 * (C) Copyright 2010 Nuxeo SAS (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Olivier Grisel
 *
 * $Id$
 */
package org.nuxeo.ecm.platform.suggestbox.jsf;

import static org.jboss.seam.ScopeType.CONVERSATION;

import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Scope;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.platform.contentview.seam.ContentViewActions;
import org.nuxeo.ecm.platform.faceted.search.jsf.FacetedSearchActions;
import org.nuxeo.ecm.platform.suggestbox.service.Suggestion;
import org.nuxeo.ecm.platform.suggestbox.service.SuggestionContext;
import org.nuxeo.ecm.platform.suggestbox.service.SuggestionException;
import org.nuxeo.ecm.platform.suggestbox.service.SuggestionHandlingException;
import org.nuxeo.ecm.platform.suggestbox.service.SuggestionService;
import org.nuxeo.ecm.platform.ui.web.api.NavigationContext;
import org.nuxeo.ecm.platform.ui.web.invalidations.AutomaticDocumentBasedInvalidation;
import org.nuxeo.ecm.platform.ui.web.invalidations.DocumentContextBoundActionBean;
import org.nuxeo.ecm.virtualnavigation.action.MultiNavTreeManager;
import org.nuxeo.runtime.api.Framework;

import edu.emory.mathcs.backport.java.util.Collections;

/**
 * Back seam component for the top right search box using the suggestion service
 * to help decode the user intent and minimize the number of clicks to find the
 * relevant information.
 */
@Name("suggestboxActions")
@Scope(CONVERSATION)
@AutomaticDocumentBasedInvalidation
public class SuggestboxActions extends DocumentContextBoundActionBean implements
        Serializable {

    public String KEYWORD_SEARCH_SUGGESTER = "searchByKeywords";

    private static final Log log = LogFactory.getLog(SuggestboxActions.class);

    private static final long serialVersionUID = 1L;

    @In(create = true, required = false)
    protected transient CoreSession documentManager;

    @In(create = true)
    protected transient NavigationContext navigationContext;

    @In(create = true)
    protected Map<String, String> messages;

    @In(create = true)
    protected Locale locale;

    @In(create = true)
    protected MultiNavTreeManager multiNavTreeManager;

    @In(create = true)
    protected FacetedSearchActions facetedSearchActions;

    @In(create = true)
    protected ContentViewActions contentViewActions;

    // keep suggestions in cache for maximum 10 seconds to avoid useless and
    // costly re-computation of the suggestions by rich:suggestionbox at
    // selection time
    protected Cached<List<Suggestion>> cachedSuggestions = new Cached<List<Suggestion>>(
            10000);

    public DocumentModel getDocumentModel(String id) throws ClientException {
        return documentManager.getDocument(new IdRef(id));
    }

    protected String searchKeywords = "";

    public String getSearchKeywords() {
        return searchKeywords;
    }

    public void setSearchKeywords(String searchKeywords) {
        this.searchKeywords = searchKeywords;
    }
    
    protected SuggestionContext getSuggestionContext() {
        SuggestionContext ctx = new SuggestionContext("searchbox",
                documentManager.getPrincipal()).withSession(documentManager).withCurrentDocument(
                navigationContext.getCurrentDocument()).withLocale(locale).withMessages(
                messages);
        return ctx;
    }

    /**
     * Callback for the ajax keypress event that triggers the generation of
     * context sensitive action suggestions. The most specific actions (e.g.
     * direct navigation to a document with matching titles) should be suggested
     * in the first position and more generic (traditional free-text search for
     * documents) last.
     */
    @SuppressWarnings("unchecked")
    public List<Suggestion> getSuggestions(Object input) {
        if (cachedSuggestions.hasExpired(input, locale)) {
            SuggestionService service = Framework.getLocalService(SuggestionService.class);
            SuggestionContext ctx = getSuggestionContext();
            try {
                List<Suggestion> suggestions = service.suggest(
                        input.toString(), ctx);
                cachedSuggestions.cache(suggestions, input, locale);
            } catch (SuggestionException e) {
                // log the exception rather than trying to display it since this
                // method is called by ajax events when typing in the searchbox.
                log.error(e, e);
                return Collections.emptyList();
            }
        }
        return cachedSuggestions.value;
    }

    /**
     * Callback for the ajax selection of an item in the rich:suggestionbox
     * list.
     */
    public Object handleSelection(Suggestion selectedSuggestion)
            throws SuggestionHandlingException {
        SuggestionService service = Framework.getLocalService(SuggestionService.class);
        SuggestionContext ctx = getSuggestionContext();
        // reset the search field on explicit selection from the list.
        this.searchKeywords = "";
        return service.handleSelection(selectedSuggestion, ctx);
    }

    /**
     * Action listener for the old-style search button.
     */
    public Object performKeywordsSearch() throws SuggestionException,
            SuggestionHandlingException {
        // make it possible to override how the default search is performed by
        // using the suggestion service
        SuggestionService service = Framework.getLocalService(SuggestionService.class);
        SuggestionContext context = getSuggestionContext();
        List<Suggestion> suggestions = service.suggest(searchKeywords, context,
                KEYWORD_SEARCH_SUGGESTER);
        if (suggestions.size() != 1) {
            throw new SuggestionException(String.format(
                    "Expected 1 keyword search suggestion, got %d",
                    suggestions.size()));
        }
        return service.handleSelection(suggestions.get(0), context);
    }

    @Override
    protected void resetBeanCache(DocumentModel newCurrentDocumentModel) {
        cachedSuggestions.expire();
    }

}

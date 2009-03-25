/*
 * This file is part of ACE View.
 * Copyright 2008-2009, Attempto Group, University of Zurich (see http://attempto.ifi.uzh.ch).
 *
 * ACE View is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * ACE View is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with ACE View.
 * If not, see http://www.gnu.org/licenses/.
 */

package ch.uzh.ifi.attempto.aceview;

import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.coode.manchesterowlsyntax.ManchesterOWLSyntaxEditorParser;
import org.protege.editor.core.ProtegeApplication;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.description.OWLExpressionParserException;
import org.protege.editor.owl.model.find.EntityFinder;
import org.protege.editor.owl.model.parser.ParserUtil;
import org.protege.editor.owl.model.parser.ProtegeOWLEntityChecker;
import org.protege.editor.owl.ui.renderer.OWLRendererPreferences;
//import org.semanticweb.owl.apibinding.OWLManager;
import org.semanticweb.owl.expression.ParserException;
import org.semanticweb.owl.io.OWLRendererException;
import org.semanticweb.owl.model.OWLAnnotation;
import org.semanticweb.owl.model.OWLAnnotationAxiom;
import org.semanticweb.owl.model.OWLAxiom;
import org.semanticweb.owl.model.OWLAxiomAnnotationAxiom;
import org.semanticweb.owl.model.OWLAxiomChange;
import org.semanticweb.owl.model.OWLClass;
import org.semanticweb.owl.model.OWLDataFactory;
import org.semanticweb.owl.model.OWLDataProperty;
import org.semanticweb.owl.model.OWLEntity;
import org.semanticweb.owl.model.OWLIndividual;
import org.semanticweb.owl.model.OWLLogicalAxiom;
import org.semanticweb.owl.model.OWLObjectProperty;
import org.semanticweb.owl.model.OWLOntology;
import org.semanticweb.owl.model.OWLOntologyChangeException;
import org.semanticweb.owl.model.OWLOntologyCreationException;
import org.semanticweb.owl.model.OWLOntologyManager;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import ch.uzh.ifi.attempto.ace.ACESentence;
import ch.uzh.ifi.attempto.aceview.lexicon.ACELexicon;
import ch.uzh.ifi.attempto.aceview.lexicon.EntryType;
import ch.uzh.ifi.attempto.aceview.model.event.ACESnippetEvent;
import ch.uzh.ifi.attempto.aceview.model.event.ACESnippetListener;
import ch.uzh.ifi.attempto.aceview.model.event.ACETextChangeEvent;
import ch.uzh.ifi.attempto.aceview.model.event.ACETextManagerListener;
import ch.uzh.ifi.attempto.aceview.model.event.EventType;
import ch.uzh.ifi.attempto.aceview.model.event.SnippetEventType;
import ch.uzh.ifi.attempto.owl.VerbalizerWebservice;

/**
 * <p>ACE text manager allows snippets to be added to and removed from
 * the ACE texts so that the corresponding ontology is updated
 * by adding/removing the affected axioms.</p>
 * 
 * <p>Note: selected snippet is independent from the ACE text, e.g. we can select a snippet
 * which does not belong to any text, e.g. entailed snippets are such.</p>
 * 
 * TODO: move utilities like {@link #isShow(OWLEntity)} to separate class
 * 
 * @author Kaarel Kaljurand
 */
public final class ACETextManager {
	private static final Logger logger = Logger.getLogger(ACETextManager.class);

	public static final URI acetextURI = URI.create("http://attempto.ifi.uzh.ch/acetext#acetext");
	private static final URI timestampURI = URI.create("http://purl.org/dc/elements/1.1/date");

	private static final URI entityKnow = URI.create("http://attempto.ifi.uzh.ch/ace#know");
	private static final URI entitySuperman = URI.create("http://attempto.ifi.uzh.ch/ace#Superman");

	private static final Map<URI, ACEText> acetexts = Maps.newHashMap();
	private static OWLModelManager owlModelManager;
	private static URI activeACETextURI;

	// BUG: maybe we should get a new instance whenever we need to query the renderer preferences?
	private static final OWLRendererPreferences owlRendererPreferences = OWLRendererPreferences.getInstance();

	// One snippet can be singled out by "selecting" it.
	// In this case, selectedSnippet refers to it.
	private static ACESnippet selectedSnippet;

	// One snippet can be singled out with the "Why?"-button.
	// In this case, whySnippet refers to it.
	private static ACESnippet whySnippet;


	private static final List<ACETextManagerListener> aceTextManagerChangeListeners = Lists.newArrayList();
	private static final List<ACESnippetListener> snippetListeners = Lists.newArrayList();

	private static boolean isInitCompleted = false;

	// No instances allowed
	private ACETextManager() {}

	public static void createACEText(URI uri) {
		ACEText acetext = new ACETextImpl();
		acetexts.put(uri, acetext);
		// BUG: would be better if we didn't have to set the active URI here
		activeACETextURI = uri;
	}

	/*
	public static void createACEText(URI uri) {
		// If the given URI is already registered
		if (acetexts.containsKey(uri)) {
			// Do nothing.
		}
		else {
			acetexts.put(uri, new ACEText());
			acelexicons.put(uri, new ACELexicon());
		}
	}
	 */


	public static void setActiveACETextURI(URI uri) {
		if (activeACETextURI.compareTo(uri) != 0) {
			activeACETextURI = uri;
			fireEvent(EventType.ACTIVE_ACETEXT_CHANGED);
		}
	}

	/*
	public static void setActiveACETextURI(URI uri) {
		// If the given URI is already registered
		if (acetexts.containsKey(uri)) {
			// If the URI does not match the active ACE text URI
			if (activeACETextURI.compareTo(uri) != 0) {
				activeACETextURI = uri;
				fireEvent(EventType.ACTIVE_ACETEXT_CHANGED);
			}
		}
		// If the URI is not registered then create a new ACE text
		else {
			createACEText(uri);
		}
	}
	 */

	/**
	 * <p>Returns the URI of the active ACE text.
	 * In case no ACE text has been set as active
	 * then returns <code>null</code>.</p>
	 * 
	 * BUG: The latter case cannot happen though, I think.
	 * 
	 * @return URI of the active ACE text.
	 */
	public static URI getActiveACETextURI() {
		return activeACETextURI;
	}

	public static ACEText getActiveACEText() {
		return getACEText(getActiveACETextURI());
	}

	public static ACEText getACEText(URI uri) {
		if (uri == null) {
			logger.error("getACEText: URI == null; THIS SHOULD NOT HAPPEN");
			return new ACETextImpl();
		}
		ACEText acetext = acetexts.get(uri);
		if (acetext == null) {
			logger.error("acetext == null, where URI: " + uri);
			createACEText(uri);
			return getACEText(uri);
		}
		return acetext;
	}

	public static ACELexicon getActiveACELexicon() {
		return getACEText(activeACETextURI).getACELexicon();
	}

	public static void setOWLModelManager(OWLModelManager mm) {
		owlModelManager = mm;
	}

	public static OWLModelManager getOWLModelManager() {
		return owlModelManager;
	}


	/**
	 * <p>Adds the given snippet to the active ACE text, and
	 * adds the axioms of the snippet to the ontology that corresponds
	 * to the ACE text.</p>
	 * 
	 * @param snippet ACE snippet
	 */
	public static void add(ACESnippet snippet) {
		add(getActiveACEText(), snippet);
	}


	/*
	private static void add(int index, ACESnippet snippet) {
		getActiveACEText().add(index, snippet);
		changeOntology(getAddChanges(owlModelManager.getActiveOntology(), snippet));
		fireEvent(EventType.ACETEXT_CHANGED);
	}
	 */


	/**
	 * <p>Adds the given snippet to the given ACE text, and
	 * adds the axioms of the snippet to the ontology that corresponds
	 * to the ACE text.</p>
	 * 
	 * @param acetext ACE text
	 * @param snippet ACE snippet
	 */
	private static void add(ACEText acetext, ACESnippet snippet) {
		acetext.add(snippet);
		changeOntology(getAddChanges(owlModelManager.getActiveOntology(), snippet));
		fireEvent(EventType.ACETEXT_CHANGED);
	}


	/**
	 * <p>Creates a new snippet from the given OWL axiom, and
	 * adds the snippet to the given ACE text.
	 * See also {@link #add(ACEText, ACESnippet)}.</p>
	 * 
	 * @param acetext ACE text
	 * @param axiom OWL logical axiom
	 */
	public static void add(ACEText acetext, OWLLogicalAxiom axiom) {		
		AxiomVerbalizer axiomVerbalizer = getAxiomVerbalizer(acetext.getACELexicon());
		OWLModelManager mm = getOWLModelManager();
		OWLOntology ont = mm.getActiveOntology();
		ACESnippet snippet = null;
		try {
			snippet = axiomVerbalizer.verbalizeAxiom(ont.getURI(), axiom);
			add(acetext, snippet);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}


	public static void remove(ACESnippet snippet) {
		Set<OWLLogicalAxiom> removedAxioms = getActiveACEText().remove(snippet);
		changeOntology(getRemoveChanges(owlModelManager.getActiveOntology(), removedAxioms));
		fireEvent(EventType.ACETEXT_CHANGED);
	}


	/**
	 * <p>Updates the given snippet in the active text at the given index
	 * by first removing the snippet,
	 * then creating a new snippet out of the set of given sentences, and then
	 * adding the new snippet to the text and setting it as the selected snippet.</p>
	 * 
	 * TODO: require the ACE text to be passed as an argument
	 * 
	 * @param index Index of the snippet in the ACE text
	 * @param snippet Snippet to be updated (i.e replaced)
	 * @param sentences Sentences that form the new snippet
	 */
	public static void update(int index, ACESnippet snippet, List<ACESentence> sentences) {
		ACESnippet newSnippet = new ACESnippetImpl(snippet.getDefaultNamespace(), sentences);
		ACEText acetext = getActiveACEText();
		logger.info("Del old snippet: " + snippet);
		Set<OWLLogicalAxiom> removedAxioms = acetext.remove(snippet);
		logger.info("Add new snippet: " + newSnippet);
		acetext.add(index, newSnippet);

		OWLOntology ontology = owlModelManager.getActiveOntology();
		List<OWLAxiomChange> changes = Lists.newArrayList();
		changes.addAll(getRemoveChanges(ontology, removedAxioms));
		changes.addAll(getAddChanges(ontology, newSnippet));
		changeOntology(changes);
		setSelectedSnippet(newSnippet);
		fireEvent(EventType.ACETEXT_CHANGED);
	}


	public static void addAndRemoveSentences(Collection<ACESentence> addedSentences, Collection<ACESentence> removedSentences) {
		ACEText activeAceText = getActiveACEText();
		List<OWLAxiomChange> changes = Lists.newArrayList();

		for (ACESentence sentence : addedSentences) {
			ACESnippet snippet = new ACESnippetImpl(activeACETextURI, sentence);
			activeAceText.add(snippet);
			changes.addAll(getAddChanges(owlModelManager.getActiveOntology(), snippet));
		}

		for (ACESentence sentence : removedSentences) {
			changes.addAll(findAndRemove(sentence));
		}

		if (! (addedSentences.isEmpty() && removedSentences.isEmpty())) {
			changeOntology(changes);
			fireEvent(EventType.ACETEXT_CHANGED);
		}
	}


	public static void addListener(ACETextManagerListener listener) {
		aceTextManagerChangeListeners.add(listener);
	}

	public static void removeListener(ACETextManagerListener listener) {
		aceTextManagerChangeListeners.remove(listener);
	}


	public static void addSnippetListener(ACESnippetListener listener) {
		snippetListeners.add(listener);
	}

	public static void removeSnippetListener(ACESnippetListener listener) {
		snippetListeners.remove(listener);
	}


	// TODO: should be private
	public static void fireEvent(EventType type) {
		if (isInitCompleted) {
			ACETextChangeEvent event = new ACETextChangeEvent(type);
			logger.info("Event: " + event.getType());
			for (ACETextManagerListener listener : aceTextManagerChangeListeners) {
				try {
					listener.handleChange(event);
				}
				catch (Exception e) {
					logger.error("Detaching " + listener.getClass().getName() + " because it threw " + e.toString());
					ProtegeApplication.getErrorLog().logError(e);
					removeListener(listener);
				}
			}
		}
	}


	private static void fireSnippetEvent(SnippetEventType type) {
		ACESnippetEvent event = new ACESnippetEvent(type);
		logger.info("Event: " + event.getType());
		for (ACESnippetListener listener : snippetListeners) {
			try {
				listener.handleChange(event);
			}
			catch (Exception e) {
				logger.error("Detaching " + listener.getClass().getName() + " because it threw " + e.toString());
				ProtegeApplication.getErrorLog().logError(e);
				removeSnippetListener(listener);
			}
		}
	}


	public static void addToOntology(OWLOntologyManager ontologyManager, OWLOntology ontology, Set<? extends OWLAxiom> axioms) {
		List<AddAxiomByACEView> changes = Lists.newArrayList();
		for (OWLAxiom ax : axioms) {
			changes.add(new AddAxiomByACEView(ontology, ax));
		}
		changeOntology(ontologyManager, changes);
	}


	/**
	 * TODO: We currently catch the change exception here.
	 * Find out what can the exception actually inform us about,
	 * and how can we recover from that. Otherwise we could
	 * also raise a runtime exception here.
	 * 
	 * @param owlOntologyManager
	 * @param changes
	 */
	public static void changeOntology(OWLOntologyManager owlOntologyManager, List<? extends OWLAxiomChange> changes) {
		try {
			owlOntologyManager.applyChanges(changes);
		} catch (OWLOntologyChangeException e) {
			e.printStackTrace();
		}
	}

	private static void changeOntology(List<? extends OWLAxiomChange> changes) {
		changeOntology(owlModelManager.getOWLOntologyManager(), changes);
	}


	// We make a defensive copy here, otherwise we would get a
	// ConcurrentModificationException
	private static List<OWLAxiomChange> findAndRemove(ACESentence sentence) {
		ACEText acetext = getActiveACEText();
		OWLOntology ontology = owlModelManager.getActiveOntology();
		List<OWLAxiomChange> changes = Lists.newArrayList();

		for (ACESnippet oldSnippet : ImmutableSet.copyOf(acetext.getSentenceSnippets(sentence))) {
			Set<OWLLogicalAxiom> removedAxioms = acetext.remove(oldSnippet);
			changes.addAll(getRemoveChanges(ontology, removedAxioms));

			if (oldSnippet.getSentences().size() > 1) {
				logger.info("Found super snippet: " + oldSnippet.toString());
				List<ACESentence> sentences = oldSnippet.getRest(sentence);
				ACESnippet snippet = new ACESnippetImpl(activeACETextURI, sentences);
				acetext.add(snippet);
				changes.addAll(getAddChanges(ontology, snippet));
			}
		}
		return changes;
	}


	public static OWLOntology createOntologyFromAxioms(OWLOntologyManager ontologyManager, URI uri, Set<OWLAxiom> axioms) throws OWLOntologyCreationException, OWLOntologyChangeException {
		return ontologyManager.createOntology(axioms, uri);
	}

	/*
	private static OWLOntology createOntology(URI uri) throws OWLOntologyCreationException {
		return createOntology(OWLManager.createOWLOntologyManager(), uri);
	}
	 */

	/**
	 * <p>Creates a new ontology with a dummy URI.</p>
	 * 
	 * TODO: OWL-API can currently invent URIs but requires
	 * the set of initial axioms to be provided.
	 * The interface specifies OWLOntologyChangeException which cannot happen
	 * in case of empty set of axioms, but which we need to catch anyway.
	 * 
	 * @param ontologyManager OWL ontology manager
	 * @return OWL ontology
	 * @throws OWLOntologyCreationException
	 */
	public static OWLOntology createOntology(OWLOntologyManager ontologyManager) throws OWLOntologyCreationException {
		try {
			return ontologyManager.createOntology(Collections.<OWLAxiom>emptySet());
		} catch (OWLOntologyChangeException e) {
			return null; // Cannot happen
		}
	}

	/*
	private static OWLOntology createOntology(OWLOntologyManager ontologyManager, URI uri) throws OWLOntologyCreationException {
		return ontologyManager.createOntology(uri);
	}
	 */

	private static OWLAxiomAnnotationAxiom createAxiomAnnotation(OWLAxiom logicalAxiom, URI uri, String str) {
		OWLAnnotation ann = null;
		OWLDataFactory df = owlModelManager.getOWLDataFactory();
		ann = df.getOWLConstantAnnotation(uri, df.getOWLUntypedConstant(str));
		return df.getOWLAxiomAnnotationAxiom(logicalAxiom, ann);
	}


	// BUG: these HTML-generators should be in some other class
	private static String getHtmlHead() {
		String fontName = owlRendererPreferences.getFontName();
		int fontSize = owlRendererPreferences.getFontSize();
		return 	"<style type='text/css'>" +
		"	body { font-size: " + fontSize +"; font-family: " + fontName + "; margin-left: 4px; margin-right: 4px; margin-top: 4px; margin-bottom: 4px; background-color: #ffffee }" +
		"	table { border-width: 1px; border-style: solid; border-color: silver; empty-cells: show; border-collapse: collapse; margin-bottom: 1em }" +
		"	td { border-width: 1px; border-style: solid; border-color: silver }" +
		"	div { padding-left: 4px; padding-right: 4px; padding-top: 4px; padding-bottom: 4px;" +
		"		margin-left: 4px; margin-right: 4px; margin-top: 4px; margin-bottom: 4px }" +
		"	div.messages { border-width: 3px; border-color: red  }" +
		"	p.question { color: olive }" +
		"	.indent { margin-left: 15px }" +
		"	.error { color: red }" +
		"	a { text-decoration: none }" +
		"</style>";
	}

	public static String wrapInHtml(String body) {
		return wrapInHtml(getHtmlHead(), body);
	}

	private static String wrapInHtml(String head, String body) {
		return "<html><head>" + head + "</head><body>" + body + "</body></html>";
	}


	/**
	 * <p>Specifies entities which should be displayed as ACE content words.</p>
	 * 
	 * <p>Note: we do not show the morph annotations of the following entities:
	 * owl:Thing, owl:Nothing, ace:Superman, ace:know.</p>
	 * 
	 * TODO: What do to with anonymous individuals?
	 * 
	 * @param entity OWL entity
	 * @return <code>true</code> if entity should be shown
	 */
	public static boolean isShow(OWLEntity entity) {

		// isClass && !entity.asOWLClass().isOWLThing() && !entity.asOWLClass().isOWLNothing() ||
		if (entity.isBuiltIn()) {
			return false;
		}

		if (entity.isOWLDataType()) {
			return false;
		}

		return (
				entity.isOWLClass() ||
				entity.isOWLObjectProperty() && !entity.getURI().equals(entityKnow) ||
				entity.isOWLDataProperty() ||
				entity.isOWLIndividual() && !entity.getURI().equals(entitySuperman)
		);
	}


	/**
	 * <p>Specifies axioms which should be "shown". For example we do not want to verbalize
	 * (entailed) axioms which contain "tricks" like using entities `know' and `Superman'.</p>
	 * 
	 * @param axiom OWL axiom
	 * @return true if axiom should be "shown"
	 */
	public static boolean isShow(OWLAxiom axiom) {
		for (OWLEntity entity : axiom.getReferencedEntities()) {
			if (entity.getURI().equals(entityKnow)) {
				return false;
			}
			if (entity.getURI().equals(entitySuperman)) {
				return false;
			}
		}
		return true;
	}


	/**
	 * <p>Finds (a single) OWL entity based on the <code>EntryType</code> and a lemma of
	 * an ACE word.</p>
	 * 
	 * FIXED: now using "false" in the getMatching*() calls.
	 * We are interested in an exact match and not a prefix or regexp match.
	 * Note that <code>getEntities(String)</code> does either wildcard or regexp matching,
	 * depending on the preferences. Therefore, we should escape all the
	 * wildcard symbols in the content words before we start matching.
	 * Maybe there is a less powerful entity finder somewhere, we don't really
	 * need regexp support when clicking on the words.
	 * 
	 * TODO: Get rid of this method. It is only used by WordsHyperlinkListener, which
	 * we should also remove, and replace it with a view which can hold the entities and
	 * thus does not have search them via some string-based encoding (which is slow).
	 *
	 * @param type Type (word class) of the lemma
	 * @param lemma Lemma of a word
	 * @return A single OWL entity that corresponds to the type-lemma combination
	 */
	public static OWLEntity findEntity(EntryType type, String lemma) {
		if (lemma != null) {
			Set<? extends OWLEntity> entities;
			EntityFinder entityFinder = getOWLModelManager().getEntityFinder();
			switch (type) {
			case CN:
				entities = entityFinder.getMatchingOWLClasses(lemma, false);
				break;
			case TV:
				entities = entityFinder.getMatchingOWLObjectProperties(lemma, false);
				if (entities == null || entities.isEmpty()) {
					entities = entityFinder.getMatchingOWLDataProperties(lemma, false);
				}
				break;
			case PN:
				entities = entityFinder.getMatchingOWLIndividuals(lemma, false);
				break;
			default:
				throw new RuntimeException("findEntity: Programmer error");
			}

			if (entities != null) {
				for (OWLEntity entity : entities) {
					if (entity.toString().equals(lemma)) {
						return entity;
					}
				}
			}
		}
		return null;
	}


	public static EntryType getLexiconEntryType(OWLEntity entity) {
		if (entity instanceof OWLClass) {
			return EntryType.CN;
		}
		else if (entity instanceof OWLObjectProperty || entity instanceof OWLDataProperty) {
			return EntryType.TV;
		}
		else if (entity instanceof OWLIndividual) {
			return EntryType.PN;
		}
		// BUG: throw an exception instead
		return EntryType.CN;
	}


	/**
	 * <p>Returns an identifier that is constructed from the lexicon type and
	 * the name of the entity.
	 * This identifier makes a difference between punned entities.
	 * It is intended for the a-element in the HTML views
	 * where entities are used as links.</p>
	 * 
	 * TODO: instead of lexicon entry type, use the entity type (class, object property, ...)
	 * TODO: instead of toString() use getURI() to get a true identifier
	 * 
	 * @param entity
	 * @return Identifier constructed from the entity type and entity name
	 */
	public static String getHrefId(OWLEntity entity) {
		EntryType type = ACETextManager.getLexiconEntryType(entity);
		return type + ":" + entity.toString();
	}


	public static Set<URI> getAnnotationURIs(OWLOntology ontology, OWLEntity entity) {
		Set<URI> annotationURIs = Sets.newHashSet();
		for (OWLAnnotation annotation : entity.getAnnotations(ontology)) {
			annotationURIs.add(annotation.getAnnotationURI());
		}
		return annotationURIs;
	}


	public static void setSelectedSnippet(OWLLogicalAxiom axiom) throws OWLRendererException, OWLOntologyCreationException, OWLOntologyChangeException {
		ACESnippet snippet = makeSnippetFromAxiom(axiom);
		setSelectedSnippet(snippet);
	}


	/**
	 * <p>Selects the given snippet. Note that if the given snippet
	 * is already selected, then it is reselected. This is needed
	 * in order to refresh the views, if the only change was
	 * in terms of axiom annotations added to the snippet's axiom.</p>
	 * 
	 * @param snippet ACE snippet
	 */
	public static void setSelectedSnippet(ACESnippet snippet) {
		selectedSnippet = snippet;
		logger.info("Selected: " + snippet);
		fireSnippetEvent(SnippetEventType.SELECTED_SNIPPET_CHANGED);
	}


	public static ACESnippet getSelectedSnippet() {
		return selectedSnippet;
	}

	public static void setWhySnippet(ACESnippet snippet) {
		whySnippet = snippet;
		logger.info("Why: " + snippet);
		fireSnippetEvent(SnippetEventType.WHY_SNIPPET_CHANGED);
	}


	public static ACESnippet getWhySnippet() {
		return whySnippet;
	}

	public static void setInitCompleted(boolean b) {
		isInitCompleted = b;
	}

	/**
	 * <p>Parses a <code>String</code> with the Manchester OWL Syntax parser and
	 * returns the corresponding <code>OWLAxiom</code> or throws an exception if
	 * parsing failed. The <code>String</code> is assumed to correspond to an OWL axiom,
	 * i.e. we use only the following methods to obtain the result.</p>
	 * 
	 * <ul>
	 * <li>parsePropertyChainSubPropertyAxiom()</li>
	 * <li>parseClassAxiom()</li>
	 * <li>parseObjectPropertyAxiom()</li>
	 * </ul>
	 * 
	 * TODO: add support for parsePropertyChainSubPropertyAxiom and parseObjectPropertyAxiom
	 * (tried but did not work).
	 * 
	 * TODO: pass URI as an argument
	 * 
	 * @param str String that possibly represents an OWL axiom in Manchester OWL Syntax
	 * @return <code>OWLAxiom</code> that corresponds to the given string.
	 * @throws OWLExpressionParserException 
	 */
	private static OWLLogicalAxiom parseWithManchesterSyntaxParser(String str) throws OWLExpressionParserException {
		OWLModelManager mngr = getOWLModelManager();
		ManchesterOWLSyntaxEditorParser parser = new ManchesterOWLSyntaxEditorParser(mngr.getOWLDataFactory(), str);
		parser.setOWLEntityChecker(new ProtegeOWLEntityChecker(mngr));
		parser.setBase(mngr.getActiveOntology().getURI().toString());
		try {
			OWLAxiom axiom = parser.parseClassAxiom();
			if (axiom instanceof OWLLogicalAxiom) {
				return (OWLLogicalAxiom) axiom;
			}
			return null;
		}
		catch (ParserException e) {
			throw ParserUtil.convertException(e);
		}
	}


	/**
	 * TODO: Currently we can only store the text and timestamp of the snippet
	 * if the snippet corresponds to a single axiom. Would be nice if a group of
	 * axioms could be annotated as well.
	 * 
	 * @param snippet
	 */
	private static List<? extends OWLAxiomChange> getAddChanges(OWLOntology ontology, ACESnippet snippet) {
		List<AddAxiomByACEView> changes = Lists.newArrayList();
		Set<OWLLogicalAxiom> snippetAxioms = snippet.getLogicalAxioms();

		for (OWLLogicalAxiom axiom : snippetAxioms) {
			changes.add(new AddAxiomByACEView(ontology, axiom));
		}

		// In case the snippet has only one axiom, then we annotate it as well
		if (snippetAxioms.size() == 1) {
			OWLLogicalAxiom axiom = snippetAxioms.iterator().next();
			OWLAxiomAnnotationAxiom annAcetext = createAxiomAnnotation(axiom, acetextURI, snippet.toString());
			OWLAxiomAnnotationAxiom annTimestamp = createAxiomAnnotation(axiom, timestampURI, snippet.getTimestamp().toString());
			changes.add(new AddAxiomByACEView(ontology, annAcetext));
			changes.add(new AddAxiomByACEView(ontology, annTimestamp));
		}

		return changes;
	}


	public static List<? extends OWLAxiomChange> getRemoveChanges(OWLOntology ontology, Set<? extends OWLAxiom> axioms) {
		List<RemoveAxiomByACEView> changes = Lists.newArrayList();
		for (OWLAxiom ax : axioms) {
			// Remove only if contains.
			if (ontology.containsAxiom(ax)) {
				changes.add(new RemoveAxiomByACEView(ontology, ax));
			}
			else {
				logger.error("Cannot remove, ontology does not contain: " + ax);
			}
		}
		return changes;
	}


	/**
	 * <p>Converts the given OWL logical axiom into the corresponding ACE snippet.</p>
	 *  
	 * TODO: We should not necessarily set the namespace to be equivalent to the
	 * active ontology namespace.
	 * 
	 * @param axiom OWL axiom
	 * @return ACE snippet that corresponds to the given OWL axiom
	 * @throws OWLRendererException
	 * @throws OWLOntologyCreationException
	 * @throws OWLOntologyChangeException
	 */
	public static ACESnippet makeSnippetFromAxiom(OWLLogicalAxiom axiom) throws OWLRendererException, OWLOntologyCreationException, OWLOntologyChangeException {
		AxiomVerbalizer axiomVerbalizer = getAxiomVerbalizer(getActiveACEText().getACELexicon());
		return axiomVerbalizer.verbalizeAxiom(getActiveACETextURI(), axiom);
	}


	private static AxiomVerbalizer getAxiomVerbalizer(ACELexicon lexicon) {
		return new AxiomVerbalizer(
				new VerbalizerWebservice(ACEPreferences.getInstance().getOwlToAce()), lexicon);
	}


	public static void resetSelectedSnippet() {
		selectedSnippet = null;
		fireSnippetEvent(SnippetEventType.SELECTED_SNIPPET_CHANGED);		
	}


	public static void processTanglingAxioms(ACEText acetext, Set<OWLLogicalAxiom> tanglingAxioms) {		
		AxiomVerbalizer axiomVerbalizer = getAxiomVerbalizer(acetext.getACELexicon());
		OWLModelManager mm = getOWLModelManager();
		OWLOntology ont = mm.getActiveOntology();

		for (OWLLogicalAxiom newAxiom : tanglingAxioms) {
			logger.info("Adding back: " + newAxiom);
			verbalizeAndAdd(acetext, ont, axiomVerbalizer, newAxiom);
		}
	}


	/**
	 * <p>Note that we do not add the axiom into the ontology, because
	 * we expect it to be there already, as it is one of the tangling
	 * axioms.</p>
	 * 
	 * @param ont
	 * @param axiomVerbalizer
	 * @param axiom
	 */
	private static void verbalizeAndAdd(ACEText acetext, OWLOntology ont, AxiomVerbalizer axiomVerbalizer, OWLLogicalAxiom axiom) {
		ACESnippet snippet = null;
		try {
			snippet = axiomVerbalizer.verbalizeAxiom(ont.getURI(), axiom);
		} catch (Exception e) {
			e.printStackTrace();
		}
		if (snippet != null) {
			acetext.add(snippet);
			OWLAxiomAnnotationAxiom annAcetext = createAxiomAnnotation(axiom, acetextURI, snippet.toString());
			OWLAxiomAnnotationAxiom annTimestamp = createAxiomAnnotation(axiom, timestampURI, snippet.getTimestamp().toString());
			List<OWLAxiomChange> changes = Lists.newArrayList();
			changes.add(new AddAxiomByACEView(ont, annAcetext));
			changes.add(new AddAxiomByACEView(ont, annTimestamp));
			changeOntology(changes);
		}
		else {
			logger.warn("AxiomVerbalizer produced a null-snippet for: " + axiom.toString());
		}
	}

	/**
	 * <p>Returns a list of annotations that are contained by the
	 * axiom annotation axioms for the logical axioms of the given snippet.
	 * Only the ACE text annotation is not returned because this is already
	 * explicitly present in the snippet.</p>
	 *  
	 * TODO: We should return the annotations from the ontology that corresponds to the
	 * text that contains this snippet (not from all the active ontologies). Or even better,
	 * we should store the annotations together with the snippet, so that the annotations
	 * would be independent from the ontology but only depend on the snippet.
	 * 
	 * TODO: Why do we return a list? Because it is simpler to update a table model in this way
	 * 
	 * @return List of annotations for the given snippet
	 */
	public static List<OWLAnnotation> getAnnotations(ACESnippet snippet) {
		List<OWLAnnotation> annotations = Lists.newArrayList();
		OWLOntology ont = ACETextManager.getOWLModelManager().getActiveOntology();
		for (OWLLogicalAxiom ax : snippet.getLogicalAxioms()) {
			for (OWLAxiomAnnotationAxiom axannax : ont.getAnnotations(ax)) {
				OWLAnnotation annotation = axannax.getAnnotation();
				if (! annotation.getAnnotationURI().equals(ACETextManager.acetextURI)) {
					annotations.add(annotation);
				}
			}
		}
		return annotations;
	}


	/**
	 * <p>Returns a list of changes that would remove all the annotations
	 * from the given ontology, that annotate the given entity and have
	 * the given URI as the annotation URI.</p>
	 * 
	 * @param ont OWL ontology
	 * @param entity OWL entity
	 * @param uri URI of the annotation
	 * @return List of remove-changes
	 */
	public static List<RemoveAxiomByACEView> findEntityAnnotationAxioms(OWLOntology ont, OWLEntity entity, URI uri) {
		List<RemoveAxiomByACEView> axioms = Lists.newArrayList();
		for (OWLAnnotationAxiom axiom : entity.getAnnotationAxioms(ont)) {
			if (axiom.getAnnotation().getAnnotationURI().equals(uri)) {
				axioms.add(new RemoveAxiomByACEView(ont, axiom));
			}
		}
		return axioms;
	}


	/**
	 * <p>Returns the rendering of the entity, as decided by the current renderer
	 * in the model manager.</p>
	 * <p>The renderer adds quotes around strings that contains spaces
	 * (e.g. a label like "Eesti Vabariik"). We remove such quotes,
	 * otherwise it would confuse the sorter.</p>
	 * 
	 * @param entity OWL entity to be rendered
	 * @return Rendering without quotes
	 */
	public static String getRendering(OWLEntity entity) {
		String rendering = getOWLModelManager().getRendering(entity);
		if (rendering == null || rendering.length() == 0) {
			return "ACEVIEW_EMPTY_STRING";

		}
		return rendering.replace("'", "");
	}


	public static OWLLogicalAxiom parseWithMos(ACESentence sentence) throws OWLExpressionParserException {
		// Remove the last token (a dot or a question mark) of the given sentence.
		String mosStr = sentence.toMOSString();

		logger.info("Parsing with the MOS parser: " + mosStr);
		return parseWithManchesterSyntaxParser(mosStr);
	}
}
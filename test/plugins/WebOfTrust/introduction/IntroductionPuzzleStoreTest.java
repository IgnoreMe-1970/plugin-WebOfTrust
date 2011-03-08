package plugins.WebOfTrust.introduction;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import plugins.WebOfTrust.DatabaseBasedTest;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.exceptions.UnknownPuzzleException;
import plugins.WebOfTrust.introduction.IntroductionPuzzle.PuzzleType;
import plugins.WebOfTrust.introduction.captcha.CaptchaFactory1;
import freenet.support.CurrentTimeUTC;

public final class IntroductionPuzzleStoreTest extends DatabaseBasedTest {

	private IntroductionPuzzleStore mPuzzleStore;
	private List<IntroductionPuzzleFactory> mPuzzleFactories;
	private List<OwnIdentity> mOwnIdentities;
	private OwnIdentity mOwnIdentity;
	
	protected void setUp() throws Exception {
		super.setUp();
		
		mPuzzleStore = mWoT.getIntroductionPuzzleStore();
		
		mPuzzleFactories = new ArrayList<IntroductionPuzzleFactory>();		
		mPuzzleFactories.add(new CaptchaFactory1());
		mPuzzleFactories = Collections.unmodifiableList(mPuzzleFactories);
		assertEquals(1, mPuzzleFactories.size());
		
		final String uriA = "SSK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/";
		final String uriB = "SSK@R3Lp2s4jdX-3Q96c0A9530qg7JsvA9vi2K0hwY9wG-4,ipkgYftRpo0StBlYkJUawZhg~SO29NZIINseUtBhEfE,AQACAAE/";
		final String uriC = "SSK@qd-hk0vHYg7YvK2BQsJMcUD5QSF0tDkgnnF6lnWUH0g,xTFOV9ddCQQk6vQ6G~jfL6IzRUgmfMcZJ6nuySu~NUc,AQACAAE/";
		
		mOwnIdentities = new ArrayList<OwnIdentity>();
		mOwnIdentities.add(mWoT.createOwnIdentity(uriA, uriA, "B", true, "Test"));
		mOwnIdentities.add(mWoT.createOwnIdentity(uriB, uriB, "B", true, "Test"));
		mOwnIdentities.add(mWoT.createOwnIdentity(uriC, uriC, "C", true, "Test"));
		mOwnIdentities = Collections.unmodifiableList(mOwnIdentities);
		
		mOwnIdentity = mOwnIdentities.get(0);
	
		assertEquals(1, mPuzzleFactories.size());
		assertEquals(3, mOwnIdentities.size());
		assertEquals(0, mPuzzleStore.getOwnCatpchaAmount(false));
		assertEquals(0, mPuzzleStore.getOwnCatpchaAmount(true));
	}
	
	/**
	 * Generates two puzzles from each {@link IntroductionPuzzleFactory} for the given OwnIdentity.
	 * It generates two per factory because some test functions need at least two puzzles and there might be only one factory.
	 * Flushes the database caches before returning.
	 * Has its own test.
	 */
	private List<OwnIntroductionPuzzle> generateNewPuzzles(final OwnIdentity identity) throws IOException {

		final ArrayList<OwnIntroductionPuzzle> result = new ArrayList<OwnIntroductionPuzzle>(mPuzzleFactories.size() + 1); 
		
		for(int factory=0; factory < mPuzzleFactories.size(); ++factory) {
			// generatePuzzle also stores them and commits the transaction
			result.add(mPuzzleFactories.get(factory).generatePuzzle(mPuzzleStore, identity));
			result.add(mPuzzleFactories.get(factory).generatePuzzle(mPuzzleStore, identity));
		}
		
		flushCaches();
		
		return Collections.unmodifiableList(result);
	}
	
	/**
	 * TODO: There seems to be no java API for this. Sucks. Such simple stuff should only be used from libraroes-
	 */
	private List<OwnIntroductionPuzzle> deepCopy(final List<OwnIntroductionPuzzle> puzzles) {
		final ArrayList<OwnIntroductionPuzzle> result = new ArrayList<OwnIntroductionPuzzle>(puzzles.size() + 1);
		for(OwnIntroductionPuzzle p : puzzles) result.add(p.clone());
		return Collections.unmodifiableList(result);
	}
	
	private IntroductionPuzzle constructPuzzle(OwnIdentity identity, Date dateOfExpiration) {
		return new IntroductionPuzzle(identity, UUID.randomUUID().toString() + "@" + identity.getID(), PuzzleType.Captcha, "image/jpeg", new byte[] { 0 }, 
				new Date(dateOfExpiration.getTime() - IntroductionServer.PUZZLE_INVALID_AFTER_DAYS * 24 * 60 * 60 * 1000), dateOfExpiration, mPuzzleStore.getFreeIndex(identity, dateOfExpiration));
	}
	
	private IntroductionPuzzle constructPuzzle() {
		return constructPuzzle(mOwnIdentity, new Date(CurrentTimeUTC.getInMillis() + 24 * 60 * 60 * 1000));
	}
	
	/**
	 * Test for utility function of this test class, not for a function of IntroductionPuzzleStore.
	 */
	public void testGenerateNewPuzzles() throws IOException {
		final OwnIdentity a = mOwnIdentities.get(0);
		final OwnIdentity b = mOwnIdentities.get(1);
		
		final int puzzleCountA = generateNewPuzzles(a).size();
		final int puzzleCountB = generateNewPuzzles(b).size() + generateNewPuzzles(b).size();
		
		assertEquals(mPuzzleFactories.size()*2, puzzleCountA);
		assertEquals(mPuzzleFactories.size()*2*2, puzzleCountB);
		
		assertEquals(puzzleCountA, mPuzzleStore.getUninsertedOwnPuzzlesByInserter(a).size());
		assertEquals(puzzleCountB, mPuzzleStore.getUninsertedOwnPuzzlesByInserter(b).size());
	}

	public void testDeleteExpiredPuzzles() throws UnknownPuzzleException, IOException {
		final List<IntroductionPuzzle> deletedPuzzles = new ArrayList<IntroductionPuzzle>();
		final Date expirationDate = new Date(CurrentTimeUTC.getInMillis() + 500);
		deletedPuzzles.add(constructPuzzle(mOwnIdentity, expirationDate));
		deletedPuzzles.add(constructPuzzle(mOwnIdentity, expirationDate));
		for(IntroductionPuzzle p : deletedPuzzles) mPuzzleStore.storeAndCommit(p);

		final List<OwnIntroductionPuzzle> notDeletedPuzzles = generateNewPuzzles(mOwnIdentity);
		
		while(CurrentTimeUTC.get().before(expirationDate)) {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) { }
		}

		mPuzzleStore.deleteExpiredPuzzles();
	
		flushCaches();
		
		for(IntroductionPuzzle puzzle : deletedPuzzles) {
			try {
				mPuzzleStore.getByID(puzzle.getID());
				fail("Puzzle was not delted");
			} catch(UnknownPuzzleException e) {}
		}
		
		for(OwnIntroductionPuzzle puzzle : notDeletedPuzzles) {
			mPuzzleStore.getByID(puzzle.getID());
		}
	}

	public void testDeleteOldestUnsolvedPuzzles() throws IOException, UnknownPuzzleException {
		long currentTime = CurrentTimeUTC.getInMillis();
		
		final List<IntroductionPuzzle> deletedPuzzles = new ArrayList<IntroductionPuzzle>();
		deletedPuzzles.add(constructPuzzle(mOwnIdentity, new Date(currentTime + 100*1000 + 1)));
		deletedPuzzles.add(constructPuzzle(mOwnIdentity, new Date(currentTime + 100*1000 + 2)));
		for(IntroductionPuzzle p : deletedPuzzles) mPuzzleStore.storeAndCommit(p);

		final List<IntroductionPuzzle> notDeletedPuzzles = new ArrayList<IntroductionPuzzle>();
		notDeletedPuzzles.add(constructPuzzle(mOwnIdentity, new Date(currentTime + 100*1000 + 3)));
		notDeletedPuzzles.add(constructPuzzle(mOwnIdentity, new Date(currentTime + 100*1000 + 4)));
		for(IntroductionPuzzle p :notDeletedPuzzles) mPuzzleStore.storeAndCommit(p);
		
		mPuzzleStore.deleteOldestUnsolvedPuzzles(2);
	
		flushCaches();
		
		for(IntroductionPuzzle puzzle : deletedPuzzles) {
			try {
				mPuzzleStore.getByID(puzzle.getID());
				fail("Puzzle was not delted");
			} catch(UnknownPuzzleException e) {}
		}
		
		for(IntroductionPuzzle puzzle : notDeletedPuzzles) {
			mPuzzleStore.getByID(puzzle.getID());
		}
	}

	public void testOnIdentityDeletion() throws IOException, UnknownIdentityException {
		final OwnIdentity a = mOwnIdentities.get(0);
		final OwnIdentity b = mOwnIdentities.get(1);
		
		final List<OwnIntroductionPuzzle> deletedPuzzles = generateNewPuzzles(a);
		final int puzzleCountB = generateNewPuzzles(b).size();
		
		mWoT.deleteIdentity(a);
		flushCaches();
		
		// We should not query for the puzzle count of the identity to ensure that we catch puzzles whose owner has become null as well.
		for(OwnIntroductionPuzzle puzzle : deletedPuzzles) {
			try {
				mPuzzleStore.getByID(puzzle.getID());
				fail("Puzzle was not deleted: " + puzzle);
			} catch(UnknownPuzzleException e) {}
		}

		assertEquals(puzzleCountB, mPuzzleStore.getUninsertedOwnPuzzlesByInserter(b).size());
	}

	public void testStoreAndCommit() throws UnknownPuzzleException {
		IntroductionPuzzle puzzle = constructPuzzle();
		
		mPuzzleStore.storeAndCommit(puzzle);
		puzzle = puzzle.clone();
		
		flushCaches();
		
		assertEquals(puzzle, mPuzzleStore.getByID(puzzle.getID()));
	}

	public void testGetByID() throws IOException, UnknownPuzzleException {
		final List<OwnIntroductionPuzzle> puzzles = generateNewPuzzles(mOwnIdentity);
		
		for(final OwnIntroductionPuzzle puzzle : puzzles) {
			assertSame(puzzle, mPuzzleStore.getByID(puzzle.getID()));
		}
		
		for(final OwnIntroductionPuzzle clone : deepCopy(puzzles)) {
			final IntroductionPuzzle original = mPuzzleStore.getByID(clone.getID());
			assertNotSame(clone, original);
			assertEquals(clone, original);
		}
		
		try {
			mPuzzleStore.getByID(UUID.randomUUID().toString());
			fail("No such puzzle should exist.");
		} catch(UnknownPuzzleException e) {} 
	}

	public void testGetPuzzleBySolutionURI() throws ParseException, UnknownIdentityException, UnknownPuzzleException, IOException {
		final List<OwnIntroductionPuzzle> puzzles = generateNewPuzzles(mOwnIdentity);
		
		for(final IntroductionPuzzle puzzle : puzzles) {
			assertSame(puzzle, mPuzzleStore.getPuzzleBySolutionURI(puzzle.getSolutionURI()));
		}
		
		for(final IntroductionPuzzle clone : deepCopy(puzzles)) {
			final IntroductionPuzzle original = mPuzzleStore.getPuzzleBySolutionURI(clone.getSolutionURI());
			assertNotSame(clone, original);
			assertEquals(clone, original);
		}
	}

	public void testGetOwnPuzzleByRequestURI() throws IOException, ParseException, UnknownIdentityException, UnknownPuzzleException {
		final List<OwnIntroductionPuzzle> puzzles = generateNewPuzzles(mOwnIdentity);
		
		for(final OwnIntroductionPuzzle puzzle : puzzles) {
			assertSame(puzzle, mPuzzleStore.getOwnPuzzleByRequestURI(puzzle.getRequestURI()));
		}
		
		for(final OwnIntroductionPuzzle clone : deepCopy(puzzles)) {
			final IntroductionPuzzle original = mPuzzleStore.getOwnPuzzleByRequestURI(clone.getRequestURI());
			assertNotSame(clone, original);
			assertEquals(clone, original);
		}
	}

	public void testGetOwnPuzzleBySolutionURI() throws ParseException, UnknownPuzzleException, IOException {
		final List<OwnIntroductionPuzzle> puzzles = generateNewPuzzles(mOwnIdentity);
		
		for(final IntroductionPuzzle puzzle : puzzles) {
			assertSame(puzzle, mPuzzleStore.getOwnPuzzleBySolutionURI(puzzle.getSolutionURI()));
		}
		
		for(final IntroductionPuzzle clone : deepCopy(puzzles)) {
			final IntroductionPuzzle original = mPuzzleStore.getOwnPuzzleBySolutionURI(clone.getSolutionURI());
			assertNotSame(clone, original);
			assertEquals(clone, original);
		}
	}

	public void testGetFreeIndex() throws IOException {
		int[] freeIndices = new int[mOwnIdentities.size()];
		
		for(int i=0; i < mOwnIdentities.size(); ++i) {
			freeIndices[i] = 0;
			
			// 0. identity => 0x generateNewPuzzles
			// 1. identity => 1x generateNewPuzzles
			// 2. identity => 2x generateNewPuzzles
			for(int j=i; j > 0; --j) {
				for(OwnIntroductionPuzzle p : generateNewPuzzles(mOwnIdentities.get(i))) {
					freeIndices[i] = Math.max(freeIndices[i], p.getIndex() + 1);
				}
			}
			
			// To check whether the free index of other days does not make the free index of today go wrong, we store a puzzle for a different day for each identity.
			mPuzzleStore.storeAndCommit(constructPuzzle(mOwnIdentities.get(i), new Date(CurrentTimeUTC.getInMillis() + 10 * IntroductionServer.PUZZLE_INVALID_AFTER_DAYS * 10 * 24 * 60 * 60 * 1000)));
		}
		
		final Date date = CurrentTimeUTC.get();
		final Date nullDate = new Date(0);
	
		for(int i=0; i < mOwnIdentities.size(); ++i) {
			assertEquals(freeIndices[i], mPuzzleStore.getFreeIndex(mOwnIdentities.get(i), date));
			assertEquals(0, mPuzzleStore.getFreeIndex(mOwnIdentities.get(i), nullDate));
		}
	}

	public void testGetUninsertedOwnPuzzlesByInserter() {
		// FIXME: Implement
	}

	public void testGetUnsolvedByInserter() {
		// FIXME: Implement
	}

	public void testGetOfTodayByInserter() {
		// FIXME: Implement
	}

	public void testGetByInserterDateIndex() {
		// FIXME: Implement
	}

	public void testGetOwnPuzzleByInserterDateIndex() {
		// FIXME: Implement
	}

	public void testGetUnsolvedPuzzles() {
		// FIXME: Implement
	}

	public void testGetUninsertedSolvedPuzzles() {
		// FIXME: Implement
	}

	public void testGetOwnCatpchaAmount() {
		// FIXME: Implement
	}

	public void testGetNonOwnCaptchaAmount() {
		// FIXME: Implement
	}

}

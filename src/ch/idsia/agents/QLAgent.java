package ch.idsia.agents;

import java.util.*;
import java.math.*;
import java.lang.Math.*;

import ch.idsia.agents.controllers.BasicMarioAIAgent;
import ch.idsia.benchmark.mario.engine.sprites.Mario;
import ch.idsia.benchmark.mario.engine.sprites.Sprite;
import ch.idsia.benchmark.mario.environments.*;
import ch.idsia.agents.QLStateAction;

public class QLAgent extends BasicMarioAIAgent implements Agent {
	static String name = "QLAgent";
	// 取り得る行動の数
	public static final int N_ACTIONS = 12;
	// J：ジャンプ S：ファイア R：右 L：左 D：下
	/*
	 * enum Action{ J, S, R, L, D, JS, JR, JL, JD, JSR, JSL, NONE, }
	 */

	// 毎フレームもっとも価値の高い行動をするが、確率epsilonで他の行動を等確率で選択
	public static final float epsilon = 0.005f;
	// もっとも良い選択の再現に使用
	private static int frameCounter = 0;
	// 毎エピソードで選択した行動を全フレーム分とっておく
	public static List<Integer> actions;
	// 学習中にもっとも良かった行動群
	public static List<Integer> best;
	public static double[][] bestQ;
	// 学習中にもっとも良かったスコア
	public static double bestScore;
	// 毎フレームで貪欲な選択をするかどうか
	public static boolean mode = false;
	// 各エピソードで、ある状態である行動を取ったかどうか
	// QLStateActionPairはint4つでstate,cliff,ableToJump,action
	// valueのIntegerはこのQLでは使わない
	public static HashMap<QLStateAction, Integer> selected;
	public static List<QLStateAction> history;
	// 行動価値関数 これを基に行動を決める
	public static HashMap<QLStateAction, Double> Q;
	public final static double INITIAL_Q = 0;
	// learning rate
	public final static double alpha = 0.1;
	// discount factor
	public final static double gamma = 0.5;
	// 各状態行動対におけるそれまで得た報酬の合計
	// public static float[][] sumValue;
	// ある状態である行動を取った回数. initialized to 1
	// public static int[][] num;

	private static float prevX, prevY;
	private static int prevCollisionsWithCreatures;
	private static int prevKillsTotal;

	public static void setMode(boolean b) {
		mode = b;
	}

	public static void ini() {
		frameCounter = 0;
		selected.clear();
		history.clear();
		actions.clear();

		// floatMarioPos might not have been initialized yet
		prevX = 0;
		prevY = 0;
		prevCollisionsWithCreatures = 0;
		prevKillsTotal = 0;
	}

	// コンストラクタ
	public QLAgent() {
		super(name);
		Q = new HashMap<QLStateAction, Double>();
		selected = new HashMap<QLStateAction, Integer>();
		history = new ArrayList<QLStateAction>();
		Random random = new Random();
		// for (int i = 0; i < QLState.N_STATES; ++i) {
		// for (int j = 0; j < N_ACTIONS; ++j) {
		// Q[i][j] = random.nextDouble() * 1000;
		// }
		// }

		actions = new ArrayList<Integer>();
		best = new ArrayList<Integer>();
	}

	public boolean[] getAction() {
		float x = marioFloatPos[0];
		float y = marioFloatPos[1];

		// give penalty for collisions
		if (Mario.collisionsWithCreatures > prevCollisionsWithCreatures) {
			givePenaltyForRecentActions(8, 100);
		}

		// give reward for kills
		if (getKillsTotal > prevKillsTotal) {
			giveRewardForRecentActions(8, 10);
		}

		clearAction();
		int currAction = 0;
		if (!mode) {
			currAction = chooseAction();
			actions.add(currAction);
			intToAction(currAction);
			final QLState state = getState();
			if (!selected.containsKey(new QLStateAction(state, currAction)))
				selected.put(new QLStateAction(state, currAction), 1);
			else
				selected.put(new QLStateAction(state, currAction),
						selected.get(new QLStateAction(state, currAction)) + 1);
			history.add(new QLStateAction(state, currAction));
		} else {
			// currAction = chooseActionG();
			if (frameCounter < best.size())
				currAction = best.get(frameCounter);
			intToAction(currAction);
		}
		frameCounter++;

		prevX = marioFloatPos[0];
		prevY = marioFloatPos[1];
		prevCollisionsWithCreatures = Mario.collisionsWithCreatures;
		prevKillsTotal = getKillsTotal;

		return action;
	}

	// update Q(`prevState`, `prevAction`) based on current state `curState`
	public static void updateQ(QLStateAction prevStateAction, QLState curState, double reward) {
		double maxQ = 0;
		for (int a = 0; a < QLAgent.N_ACTIONS; ++a) {
			maxQ = Math.max(maxQ, Q.getOrDefault(new QLStateAction(curState, a), INITIAL_Q));
		}
		Q.put(prevStateAction,
				(1 - alpha) * Q.getOrDefault(prevStateAction, INITIAL_Q) + alpha * (reward + gamma * maxQ));
	}

	// give rewards for actions taken in the recent `duration` amount of time
	public void giveRewardForRecentActions(int duration, double reward) {
		ListIterator<QLStateAction> iter = history.listIterator(history.size());
		if (!iter.hasPrevious())
			return;
		QLStateAction curPair = iter.previous();
		updateQ(curPair, getState(), reward);
		for (int i = 1; iter.hasPrevious() && i < duration; ++i) {
			QLStateAction prevPair = iter.previous();
			updateQ(prevPair, curPair.state, reward);
			curPair = prevPair;
		}
	}

	// give penalty for actions taken in the recent `duration` amount of time
	public void givePenaltyForRecentActions(int duration, double penalty) {
		giveRewardForRecentActions(duration, -penalty);
	}

	// give rewards for the entire state-action pairs
	public void giveRewardForEntireHistory(double reward) {
		giveRewardForRecentActions(history.size(), reward);
	}

	// 障害物を検出し、stateの各bitに0,1で格納
	// ここでマリオが得る情報をほとんど決めている
	// ついでにマリオが地面にいるかも取得
	// 崖検出
	public QLState getState() {
		boolean cliff = true;
		for (int i = 0; i < 10; ++i) {
			if (getReceptiveFieldCellValue(marioEgoRow + i, marioEgoCol + 1) != 0) {
				cliff = false;
				break;
			}
		}

		QLState state = new QLState(levelScene, enemies, isMarioOnGround, cliff, isMarioAbleToJump);
		return state;
	}

	// 行動価値関数を基に行動選択
	public int chooseAction() {
		float r = (float) (Math.random());
		int idx = 0;
		if (r < epsilon) {
			float sum = 0;
			float d = epsilon / (float) N_ACTIONS;
			sum += d;
			while (sum < r) {
				sum += d;
				idx++;
			}
		} else {
			double max = -Double.MAX_VALUE;
			QLState s = getState();
			for (int i = 0; i < N_ACTIONS; ++i) {
				double q = Q.getOrDefault(new QLStateAction(s, i), INITIAL_Q);
				if (q > max) {
					max = q;
					idx = i;
				}
			}
		}
		return idx;
	}

	// 行動選択前にactionを一旦全部falseにする
	public void clearAction() {
		for (int i = 0; i < Environment.numberOfKeys; ++i) {
			action[i] = false;
		}
	}

	// int(0-11)をacitonにする
	public void intToAction(int n) {
		if (n == 0 || (n > 4 && n < 11))
			action[Mario.KEY_JUMP] = true;
		if (n == 1 || n == 5 || n == 9 || n == 10)
			action[Mario.KEY_SPEED] = true;
		if (n == 2 || n == 6 || n == 9)
			action[Mario.KEY_RIGHT] = true;
		if (n == 3 || n == 7 || n == 10)
			action[Mario.KEY_LEFT] = true;
		if (n == 4 || n == 8)
			action[Mario.KEY_DOWN] = true;
	}
}

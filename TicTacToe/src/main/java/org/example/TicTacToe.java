package org.example;

import java.util.*;

/* ---------------------------- Symbol ---------------------------- */
enum Symbol {
    X, O, EMPTY;

    @Override
    public String toString() {
        return switch (this) {
            case X -> "X";
            case O -> "O";
            default -> " ";
        };
    }
}

/* ---------------------------- Position ---------------------------- */
class Position {
    final int row;
    final int col;
    Position(int row, int col) {
        this.row = row;
        this.col = col;
    }
    @Override
    public String toString() { return "(" + row + "," + col + ")"; }
}

/* ---------------------------- Board ---------------------------- */
class Board {
    private final int n;
    private final Symbol[][] grid;
    private int filledCount = 0;

    Board() { this(3); }
    Board(int n) {
        this.n = n;
        grid = new Symbol[n][n];
        for (int i = 0; i < n; i++)
            Arrays.fill(grid[i], Symbol.EMPTY);
    }

    public boolean isValidPosition(Position p) {
        return p != null && p.row >= 0 && p.row < n && p.col >= 0 && p.col < n;
    }

    public boolean isCellEmpty(Position p) {
        return isValidPosition(p) && grid[p.row][p.col] == Symbol.EMPTY;
    }

    /**
     * Place a symbol at position p if valid and empty.
     * Returns true if move succeeded.
     */
    public boolean place(Position p, Symbol s) {
        if (!isValidPosition(p) || s == Symbol.EMPTY) return false;
        if (!isCellEmpty(p)) return false;
        grid[p.row][p.col] = s;
        filledCount++;
        return true;
    }

    public boolean isFull() {
        return filledCount == n * n;
    }

    /**
     * Check whether given symbol has a winning line.
     */
    public boolean hasWinner(Symbol s) {
        // Rows
        for (int r = 0; r < n; r++) {
            boolean all = true;
            for (int c = 0; c < n; c++) {
                if (grid[r][c] != s) { all = false; break; }
            }
            if (all) return true;
        }
        // Cols
        for (int c = 0; c < n; c++) {
            boolean all = true;
            for (int r = 0; r < n; r++) {
                if (grid[r][c] != s) { all = false; break; }
            }
            if (all) return true;
        }
        // Diagonal TL-BR
        boolean all = true;
        for (int i = 0; i < n; i++) {
            if (grid[i][i] != s) { all = false; break; }
        }
        if (all) return true;
        // Diagonal TR-BL
        all = true;
        for (int i = 0; i < n; i++) {
            if (grid[i][n - 1 - i] != s) { all = false; break; }
        }
        return all;
    }

    public void printBoard() {
        System.out.println();
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                System.out.print(" " + grid[r][c].toString() + " ");
                if (c < n - 1) System.out.print("|");
            }
            System.out.println();
            if (r < n - 1) {
                System.out.println("---+---+---");
            }
        }
        System.out.println();
    }
}

/* ---------------------------- Player & Strategies ---------------------------- */
interface PlayerStrategy {
    /**
     * Return the next move position for the given board and symbol.
     * May return null to concede or if no move available.
     */
    Position nextMove(Board board, Symbol mySymbol);
}

/** Human strategy: reads "row col" from System.in 0-indexed. */
class HumanStrategy implements PlayerStrategy {
    private final Scanner scanner;
    HumanStrategy(Scanner scanner) { this.scanner = scanner; }

    @Override
    public Position nextMove(Board board, Symbol mySymbol) {
        System.out.print("Player " + mySymbol + " enter move (row col) 0-based: ");
        try {
            int r = scanner.nextInt();
            int c = scanner.nextInt();
            return new Position(r, c);
        } catch (InputMismatchException e) {
            scanner.nextLine(); // flush
            System.out.println("Invalid input. Expected two integers.");
            return null;
        }
    }
}

/** Predefined strategy: returns positions from a queue — good for tests / demo runs. */
class PredefinedStrategy implements PlayerStrategy {
    private final Queue<Position> moves;
    PredefinedStrategy(List<Position> moves) {
        this.moves = new ArrayDeque<>(moves);
    }
    @Override
    public Position nextMove(Board board, Symbol mySymbol) {
        return moves.poll();
    }
}

/** Simple random strategy (legal random moves) — useful for quick automated play. */
class RandomStrategy implements PlayerStrategy {
    private final Random rnd = new Random();
    @Override
    public Position nextMove(Board board, Symbol mySymbol) {
        List<Position> free = new ArrayList<>();
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) {
                Position p = new Position(r, c);
                if (board.isCellEmpty(p)) free.add(p);
            }
        if (free.isEmpty()) return null;

        return free.get(rnd.nextInt(free.size()));
    }
}

record Player(Symbol symbol, PlayerStrategy strategy) {
}

/* ---------------------------- GameController ---------------------------- */
class GameController {
    private final Board board;
    private final Player p1;
    private final Player p2;

    GameController(Board board, Player p1, Player p2) {
        this.board = board;
        this.p1 = p1;
        this.p2 = p2;
    }

    /** Runs the game loop; returns winner Symbol or EMPTY for draw/none. */
    public Symbol play() {
        Player current = p1;
        while (true) {
            board.printBoard();
            Position move = current.strategy().nextMove(board, current.symbol());

            if (move == null) {
                System.out.println("Player " + current.symbol() + " provided no move -> skipping / conceding.");
                // If we want, we can treat null as concede -> opponent wins
                Symbol opponent = (current == p1) ? p2.symbol() : p1.symbol();
                System.out.println("Winner by concession: " + opponent);
                return opponent;
            }

            if (!board.isValidPosition(move) || !board.isCellEmpty(move)) {
                System.out.println("Illegal move " + move + " by " + current.symbol() + ". Try again.");
                // For human, loop to ask again. For automated strategies, treat as skip (or you can decide)
                // Simpler: if illegal, ask same player again (continue).
                continue;
            }

            board.place(move, current.symbol());

            // Win check
            if (board.hasWinner(current.symbol())) {
                board.printBoard();
                System.out.println("Player " + current.symbol() + " wins!");
                return current.symbol();
            }

            if (board.isFull()) {
                board.printBoard();
                System.out.println("Game is a draw.");
                return Symbol.EMPTY;
            }

            // swap player
            current = (current == p1) ? p2 : p1;
        }
    }
}

/* ---------------------------- main (demo) ---------------------------- */
public class TicTacToe {
    public static void main(String[] args) {
        // Choose one of the following demo modes:
        // 1) Human vs Human: uncomment human mode (reads console)
        // 2) Predefined moves: demo automatic play
        // 3) Random vs Random: automated play for quick testing

        Scanner scanner = new Scanner(System.in);

        // ----- Mode 1: Human vs Human -----
        System.out.println("TicTacToe Demo - choose mode: 1) Human vs Human, 2) Predefined demo, 3) Random vs Random");
        int mode = 1;
        try {
            mode = Integer.parseInt(scanner.nextLine().trim());
        } catch (Exception ignored) {}

        Board board = new Board();
        Player p1, p2;

        if (mode == 1) {
            p1 = new Player(Symbol.X, new HumanStrategy(scanner));
            p2 = new Player(Symbol.O, new HumanStrategy(scanner));
        } else if (mode == 2) {
            // Predefined moves example (X then O then X ...)
            // You can modify these moves to test win/draw scenarios quickly.
            List<Position> movesX = Arrays.asList(new Position(0,0), new Position(1,1), new Position(2,2));
            List<Position> movesO = Arrays.asList(new Position(0,1), new Position(0,2));
            p1 = new Player(Symbol.X, new PredefinedStrategy(movesX));
            p2 = new Player(Symbol.O, new PredefinedStrategy(movesO));
            System.out.println("Running predefined demo...");
        } else {
            p1 = new Player(Symbol.X, new RandomStrategy());
            p2 = new Player(Symbol.O, new RandomStrategy());
            System.out.println("Running random vs random demo...");
        }

        GameController controller = new GameController(board, p1, p2);
        controller.play();

        scanner.close();
    }
}

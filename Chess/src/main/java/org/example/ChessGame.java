package org.example;

// File: ChessGame.java
// Single-file, executable Java chess program (console).
// Supports: standard 8x8, two human players, move validation, check/checkmate/draw detection.
// Simplifications: no castling, no en-passant, pawn auto-promotes to Queen.
// Moves input format: "e2 e4" (from-to). Type "exit" to quit.

import java.util.*;

public class ChessGame {
    public static void main(String[] args) {
        Game game = new Game();
        game.start();
    }

    // -------------------------
    // Core types & enums
    // -------------------------
    enum Color { WHITE, BLACK }
    enum GameStatus { IN_PROGRESS, CHECK, CHECKMATE, DRAW }

    static class Position {
        int r, c; // r: 0..7 (0 is rank 8), c: 0..7 (0 is 'a')
        Position(int r, int c) { this.r = r; this.c = c; }
        Position(String algebraic) {
            // algebraic like "e2"
            this.c = algebraic.charAt(0) - 'a';
            int rank = Character.getNumericValue(algebraic.charAt(1)); // '1'..'8'
            this.r = 8 - rank;
        }
        boolean inBounds() { return r >= 0 && r < 8 && c >= 0 && c < 8; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Position)) return false;
            Position p = (Position)o;
            return p.r == r && p.c == c;
        }
        @Override public int hashCode() { return r*8 + c; }
        @Override public String toString() {
            return "" + (char)('a' + c) + (8 - r);
        }
    }

    // -------------------------
    // Piece hierarchy
    // -------------------------
    static abstract class Piece {
        Color color;
        Piece(Color color) { this.color = color; }
        abstract char symbol(); // for display
        abstract List<Position> pseudoLegalMoves(Position from, Piece[][] board);
        boolean isOpponent(Piece other) { return other != null && other.color != this.color; }
    }

    static class King extends Piece {
        King(Color c){ super(c); }
        public char symbol(){ return color==Color.WHITE ? 'K' : 'k'; }
        public List<Position> pseudoLegalMoves(Position from, Piece[][] board){
            int[] dr = {-1,-1,-1,0,0,1,1,1}, dc = {-1,0,1,-1,1,-1,0,1};
            List<Position> res = new ArrayList<>();
            for (int i=0;i<8;i++){
                Position p = new Position(from.r+dr[i], from.c+dc[i]);
                if (p.inBounds()) {
                    Piece target = board[p.r][p.c];
                    if (target == null || isOpponent(target)) res.add(p);
                }
            }
            return res;
        }
    }

    static class Queen extends Piece {
        Queen(Color c){ super(c); }
        public char symbol(){ return color==Color.WHITE ? 'Q' : 'q'; }
        public List<Position> pseudoLegalMoves(Position from, Piece[][] board){
            return slidingMoves(from, board, new int[][]{{-1,0},{1,0},{0,-1},{0,1},{-1,-1},{-1,1},{1,-1},{1,1}});
        }
    }

    static class Rook extends Piece {
        Rook(Color c){ super(c); }
        public char symbol(){ return color==Color.WHITE ? 'R' : 'r'; }
        public List<Position> pseudoLegalMoves(Position from, Piece[][] board){
            return slidingMoves(from, board, new int[][]{{-1,0},{1,0},{0,-1},{0,1}});
        }
    }

    static class Bishop extends Piece {
        Bishop(Color c){ super(c); }
        public char symbol(){ return color==Color.WHITE ? 'B' : 'b'; }
        public List<Position> pseudoLegalMoves(Position from, Piece[][] board){
            return slidingMoves(from, board, new int[][]{{-1,-1},{-1,1},{1,-1},{1,1}});
        }
    }

    static class Knight extends Piece {
        Knight(Color c){ super(c); }
        public char symbol(){ return color==Color.WHITE ? 'N' : 'n'; }
        public List<Position> pseudoLegalMoves(Position from, Piece[][] board){
            int[] dr = {-2,-2,-1,-1,1,1,2,2}, dc = {-1,1,-2,2,-2,2,-1,1};
            List<Position> res = new ArrayList<>();
            for (int i=0;i<8;i++){
                Position p = new Position(from.r+dr[i], from.c+dc[i]);
                if (p.inBounds()){
                    Piece t = board[p.r][p.c];
                    if (t == null || isOpponent(t)) res.add(p);
                }
            }
            return res;
        }
    }

    static class Pawn extends Piece {
        Pawn(Color c){ super(c); }
        public char symbol(){ return color==Color.WHITE ? 'P' : 'p'; }
        public List<Position> pseudoLegalMoves(Position from, Piece[][] board){
            List<Position> res = new ArrayList<>();
            int dir = color==Color.WHITE ? -1 : 1;
            int startRow = color==Color.WHITE ? 6 : 1;
            // single forward
            Position one = new Position(from.r + dir, from.c);
            if (one.inBounds() && board[one.r][one.c] == null) {
                res.add(one);
                // double forward
                Position two = new Position(from.r + 2*dir, from.c);
                if (from.r == startRow && board[two.r][two.c] == null) res.add(two);
            }
            // captures
            int[] dc = {-1,1};
            for (int d: dc) {
                Position cap = new Position(from.r + dir, from.c + d);
                if (cap.inBounds()) {
                    Piece t = board[cap.r][cap.c];
                    if (t != null && isOpponent(t)) res.add(cap);
                }
            }
            return res;
        }
    }

    // sliding helper
    static List<Position> slidingMoves(Position from, Piece[][] board, int[][] dirs){
        List<Position> res = new ArrayList<>();
        for (int[] d: dirs){
            int rr = from.r + d[0], cc = from.c + d[1];
            while (rr >=0 && rr <8 && cc>=0 && cc<8){
                Piece t = board[rr][cc];
                Position p = new Position(rr, cc);
                if (t == null) {
                    res.add(p);
                } else {
                    // stop after capturing opponent
                    if (t.color != board[from.r][from.c].color) res.add(p);
                    break;
                }
                rr += d[0]; cc += d[1];
            }
        }
        return res;
    }

    // -------------------------
    // Board representation
    // -------------------------
    static class Board {
        Piece[][] board = new Piece[8][8];
        Board() { setupInitial(); }
        void setupInitial(){
            // empty
            for (int r=0;r<8;r++) for (int c=0;c<8;c++) board[r][c] = null;
            // white pieces (bottom, ranks 1 and 2 -> rows 7 and 6)
            board[7][0] = new Rook(Color.WHITE);
            board[7][1] = new Knight(Color.WHITE);
            board[7][2] = new Bishop(Color.WHITE);
            board[7][3] = new Queen(Color.WHITE);
            board[7][4] = new King(Color.WHITE);
            board[7][5] = new Bishop(Color.WHITE);
            board[7][6] = new Knight(Color.WHITE);
            board[7][7] = new Rook(Color.WHITE);
            for (int c=0;c<8;c++) board[6][c] = new Pawn(Color.WHITE);

            // black pieces (top, ranks 8 and 7 -> rows 0 and 1)
            board[0][0] = new Rook(Color.BLACK);
            board[0][1] = new Knight(Color.BLACK);
            board[0][2] = new Bishop(Color.BLACK);
            board[0][3] = new Queen(Color.BLACK);
            board[0][4] = new King(Color.BLACK);
            board[0][5] = new Bishop(Color.BLACK);
            board[0][6] = new Knight(Color.BLACK);
            board[0][7] = new Rook(Color.BLACK);
            for (int c=0;c<8;c++) board[1][c] = new Pawn(Color.BLACK);
        }

        Board copy() {
            Board b2 = new Board();
            for (int r=0;r<8;r++) for (int c=0;c<8;c++) b2.board[r][c] = this.board[r][c];
            // Note: shallow copy of pieces is fine for our use (no piece state)
            return b2;
        }

        void move(Position from, Position to) {
            Piece moving = board[from.r][from.c];
            // perform move
            board[to.r][to.c] = moving;
            board[from.r][from.c] = null;
            // auto-promotion for pawn reaching last rank
            if (moving instanceof Pawn) {
                if ((moving.color == Color.WHITE && to.r == 0) || (moving.color == Color.BLACK && to.r == 7)) {
                    board[to.r][to.c] = new Queen(moving.color);
                }
            }
        }

        Position findKing(Color color) {
            for (int r=0;r<8;r++) for (int c=0;c<8;c++){
                Piece p = board[r][c];
                if (p instanceof King && p.color == color) return new Position(r,c);
            }
            return null;
        }

        void printBoard(){
            System.out.println("   a b c d e f g h");
            for (int r=0;r<8;r++){
                System.out.print((8-r) + "  ");
                for (int c=0;c<8;c++){
                    Piece p = board[r][c];
                    System.out.print((p==null?'.':p.symbol()) + " ");
                }
                System.out.println(" " + (8-r));
            }
            System.out.println("   a b c d e f g h");
        }
    }

    // -------------------------
    // Move validation & rules
    // -------------------------
    static class MoveValidator {
        // Check if king of `color` is in check on given board
        static boolean isInCheck(Board board, Color color) {
            Position kingPos = board.findKing(color);
            if (kingPos == null) return true; // should not happen
            return squareAttackedBy(board, kingPos, opposite(color));
        }

        // Is square q attacked by given color?
        static boolean squareAttackedBy(Board board, Position q, Color attackerColor) {
            // For each attacker piece, check if one of its pseudoLegalMoves hits q (note: pawns attack differently)
            for (int r=0;r<8;r++) for (int c=0;c<8;c++){
                Piece p = board.board[r][c];
                if (p == null || p.color != attackerColor) continue;
                Position from = new Position(r,c);
                if (p instanceof Pawn) {
                    int dir = (p.color==Color.WHITE) ? -1 : 1;
                    int[] dc = {-1,1};
                    for (int d: dc) {
                        Position cap = new Position(r + dir, c + d);
                        if (cap.inBounds() && cap.equals(q)) return true;
                    }
                } else {
                    List<Position> moves = p.pseudoLegalMoves(from, board.board);
                    for (Position mv: moves) if (mv.equals(q)) return true;
                }
            }
            return false;
        }

        // Validate move considering checks
        static boolean isLegalMove(Board board, Position from, Position to, Color mover) {
            if (!from.inBounds() || !to.inBounds()) return false;
            Piece p = board.board[from.r][from.c];
            if (p == null) return false;
            if (p.color != mover) return false;
            Piece target = board.board[to.r][to.c];
            if (target != null && target.color == mover) return false;

            // check pseudo-legal
            List<Position> pseudo = p.pseudoLegalMoves(from, board.board);
            boolean ok = false;
            for (Position pp : pseudo) if (pp.equals(to)) { ok = true; break; }
            if (!ok) return false;

            // simulate move and ensure king not left in check
            Board b2 = board.copy();
            b2.move(from, to);
            if (isInCheck(b2, mover)) return false;
            return true;
        }

        // Get all legal moves for given color
        static List<Move> allLegalMoves(Board board, Color color) {
            List<Move> res = new ArrayList<>();
            for (int r=0;r<8;r++) for (int c=0;c<8;c++){
                Piece p = board.board[r][c];
                if (p == null || p.color != color) continue;
                Position from = new Position(r,c);
                List<Position> pseudos = p.pseudoLegalMoves(from, board.board);
                for (Position to: pseudos) {
                    if (isLegalMove(board, from, to, color)) res.add(new Move(from,to));
                }
            }
            return res;
        }

        static Color opposite(Color c) { return c==Color.WHITE?Color.BLACK:Color.WHITE; }
    }

    // -------------------------
    // Move representation
    // -------------------------
    static class Move {
        Position from, to;
        Move(Position f, Position t) { from = f; to = t; }
        @Override public String toString() { return from + " -> " + to; }
    }

    // -------------------------
    // Game orchestration
    // -------------------------
    static class Game {
        Board board = new Board();
        Color turn = Color.WHITE;
        Scanner sc = new Scanner(System.in);

        void start(){
            System.out.println("Simple Chess (console). Input moves like: e2 e4");
            board.printBoard();

            while (true) {
                GameStatus status = evaluateStatus();
                if (status != GameStatus.IN_PROGRESS) {
                    announceStatus(status);
                    break;
                }
                System.out.println(turn + " to move.");
                System.out.print("Enter move (or 'exit'): ");
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("exit")) {
                    System.out.println("Exiting.");
                    break;
                }
                if (line.length() < 4) {
                    System.out.println("Invalid input. Use format: e2 e4");
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length < 2) {
                    System.out.println("Invalid input. Use format: e2 e4");
                    continue;
                }
                try {
                    Position from = new Position(parts[0]);
                    Position to = new Position(parts[1]);
                    if (!from.inBounds() || !to.inBounds()) { System.out.println("Out of bounds."); continue; }
                    if (!MoveValidator.isLegalMove(board, from, to, turn)) {
                        System.out.println("Illegal move.");
                        continue;
                    }
                    board.move(from, to);
                    board.printBoard();
                    // switch turn
                    turn = MoveValidator.opposite(turn);
                } catch (Exception ex) {
                    System.out.println("Invalid input or error: " + ex.getMessage());
                }
            }
        }

        GameStatus evaluateStatus() {
            // If current player (whose turn it is) is in check:
            boolean inCheck = MoveValidator.isInCheck(board, turn);
            List<Move> moves = MoveValidator.allLegalMoves(board, turn);
            if (inCheck && moves.isEmpty()) return GameStatus.CHECKMATE;
            if (!inCheck && moves.isEmpty()) return GameStatus.DRAW; // stalemate
            if (inCheck) return GameStatus.CHECK;
            return GameStatus.IN_PROGRESS;
        }

        void announceStatus(GameStatus s) {
            switch (s) {
                case CHECK: System.out.println("Check on " + turn); break;
                case CHECKMATE:
                    System.out.println("Checkmate! " + MoveValidator.opposite(turn) + " wins.");
                    break;
                case DRAW:
                    System.out.println("Draw (stalemate).");
                    break;
                default: System.out.println("Game over: " + s);
            }
        }
    }
}
